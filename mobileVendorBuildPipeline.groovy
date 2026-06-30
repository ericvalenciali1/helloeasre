pipeline {
    agent { label 'macos' }

    /*
    ============================================================
      PARAMETERS
    ============================================================
    */
    parameters {
        // --- Platform ---
        choice(name: 'PLATFORM',
               choices: ['ios', 'android'],
               description: 'Target platform')

        // --- Input artifact (uploaded by the user triggering the build) ---
        // For iOS    : zip containing the .xcarchive
        // For Android: zip containing the unsigned .apk/.aab + mapping.txt
        file(name: 'INPUT_ARTIFACT_ZIP',
             description: 'iOS: zip containing the .xcarchive  |  Android: zip containing unsigned .apk/.aab + mapping.txt')

        // --- Source control (only exportOptions.plist / precompile.sh live here) ---
        string(name: 'REPO_NAME',          defaultValue: '',        description: 'Bitbucket repository name containing exportOptions.plist / precompile.sh')
        string(name: 'BRANCH_NAME',        defaultValue: 'main',    description: 'Branch to check out')
        string(name: 'BITBUCKET_REPO_URL', defaultValue: '',        description: 'Full Bitbucket repository URL')

        // --- iOS signing ---
        string(name: 'EXPORT_OPTIONS_PLIST_PATH', defaultValue: 'exportOptions.plist', description: 'Path (within repo) to exportOptions.plist')
        string(name: 'IOS_SIGNING_IDENTITY',       defaultValue: '', description: 'iOS code signing identity / certificate name (optional override)')
        string(name: 'IOS_PROVISIONING_PROFILE',   defaultValue: '', description: 'Provisioning profile UUID or name (optional override)')

        // --- Android signing ---
        string(name: 'PRECOMPILE_SCRIPT_PATH', defaultValue: 'precompile.sh', description: 'Path (within repo) to precompile.sh')
        string(name: 'ANDROID_KEYSTORE_CRED_ID', defaultValue: 'android-release-keystore', description: 'Jenkins credentials ID for the Android keystore file')
        string(name: 'ANDROID_KEY_ALIAS',         defaultValue: '', description: 'Keystore key alias')
        // NOTE: also requires Jenkins Secret Text credential 'android-keystore-password'

        // NOTE: iOS signing also requires Jenkins Secret Text credential 'ios-keychain-password'
        // (password to unlock the macOS agent's login keychain holding the signing certificate)


        // --- Nexus IQ ---
        string(name: 'NEXUS_PROJECT_NAME', defaultValue: '', description: 'Nexus IQ project / application name')

        // --- Artifactory ---
        string(name: 'ARTIFACTORY_REPO', defaultValue: 'mobile-signed-releases', description: 'JFrog Artifactory target repository')
        string(name: 'ARTIFACT_PATH',    defaultValue: '',  description: '(Optional) Override base upload path in Artifactory')

        // --- Jira ---
        string(name: 'JIRA_PROJECT_KEY', defaultValue: '', description: 'Jira project key for the release ticket')
        string(name: 'JIRA_ISSUE_TYPE',  defaultValue: 'Task', description: 'Jira issue type for the release ticket')
        string(name: 'RELEASE_VERSION',  defaultValue: '', description: 'Release/build version label used in Jira ticket and Artifactory path')
    }

    /*
    ============================================================
      ENVIRONMENT
    ============================================================
    */
    environment {
        BITBUCKET_CREDS    = credentials('bitbucket-credentials')
        NEXUSIQ_TOKEN      = credentials('nexusiq-token')
        JFROG_CREDENTIALS  = credentials('jfrog-credentials')
        JIRA_CREDENTIALS   = credentials('jira-credentials')

        // macOS default Android SDK location (Homebrew / Android Studio installs)
        ANDROID_HOME       = "${env.ANDROID_HOME ?: "${HOME}/Library/Android/sdk"}"

        BUILD_TIMESTAMP    = sh(script: "date '+%Y%m%d%H%M%S'", returnStdout: true).trim()
        WORKDIR            = "${WORKSPACE}/work"
    }

    options {
        timestamps()
        timeout(time: 90, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    /*
    ============================================================
      STAGES
    ============================================================
    */
    stages {

        // --------------------------------------------------------
        // 1. VALIDATE PARAMETERS
        // --------------------------------------------------------
        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.REPO_NAME?.trim())          { error "REPO_NAME is required." }
                    if (!params.BITBUCKET_REPO_URL?.trim()) { error "BITBUCKET_REPO_URL is required." }
                    if (!params.NEXUS_PROJECT_NAME?.trim()) { error "NEXUS_PROJECT_NAME is required." }
                    if (!params.JIRA_PROJECT_KEY?.trim())   { error "JIRA_PROJECT_KEY is required." }

                    if (params.PLATFORM == 'android' && !params.ANDROID_KEY_ALIAS?.trim()) {
                        error "ANDROID_KEY_ALIAS is required for Android signing."
                    }

                    sh "mkdir -p ${WORKDIR}/input ${WORKDIR}/extracted ${WORKDIR}/signed ${WORKDIR}/checksums"

                    echo """
╔══════════════════════════════════════════════╗
║         SIGNING PIPELINE CONFIGURATION       ║
╠══════════════════════════════════════════════╣
║  Platform     : ${params.PLATFORM}
║  Repository   : ${params.REPO_NAME}
║  Branch       : ${params.BRANCH_NAME}
║  Release Ver  : ${params.RELEASE_VERSION ?: '(not set)'}
╚══════════════════════════════════════════════╝
                    """
                }
            }
        }

        // --------------------------------------------------------
        // 1b. VERIFY macOS TOOLCHAIN
        // --------------------------------------------------------
        stage('Verify macOS Toolchain') {
            steps {
                sh '''
                    set -e
                    echo "Host: $(sw_vers -productName) $(sw_vers -productVersion)"
                    echo "Xcode: $(xcodebuild -version | head -1)"
                    command -v unzip   >/dev/null || { echo "unzip not found"; exit 1; }
                    command -v zip     >/dev/null || { echo "zip not found"; exit 1; }
                    command -v md5     >/dev/null || { echo "md5 not found (BSD coreutils)"; exit 1; }
                    command -v shasum  >/dev/null || { echo "shasum not found"; exit 1; }
                    command -v security >/dev/null || { echo "security (keychain) not found"; exit 1; }
                    command -v codesign >/dev/null || { echo "codesign not found"; exit 1; }
                    command -v curl    >/dev/null || { echo "curl not found"; exit 1; }
                    command -v python3 >/dev/null || { echo "python3 not found"; exit 1; }
                    echo "All required macOS tools are present."
                '''
            }
        }

        // --------------------------------------------------------
        // 2. CHECKOUT (exportOptions.plist / precompile.sh only)
        // --------------------------------------------------------
        stage('Checkout Signing Assets') {
            steps {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${params.BRANCH_NAME}"]],
                    userRemoteConfigs: [[
                        url          : params.BITBUCKET_REPO_URL,
                        credentialsId: 'bitbucket-credentials'
                    ]],
                    extensions: [
                        [$class: 'CloneOption', depth: 1, shallow: true],
                        [$class: 'SparseCheckoutPaths',
                         sparseCheckoutPaths: [
                            [path: params.EXPORT_OPTIONS_PLIST_PATH],
                            [path: params.PRECOMPILE_SCRIPT_PATH]
                         ]],
                        [$class: 'CleanBeforeCheckout']
                    ]
                ])
                script {
                    if (params.PLATFORM == 'ios') {
                        if (!fileExists(params.EXPORT_OPTIONS_PLIST_PATH)) {
                            error "exportOptions.plist not found at '${params.EXPORT_OPTIONS_PLIST_PATH}' in the repository."
                        }
                    } else {
                        if (!fileExists(params.PRECOMPILE_SCRIPT_PATH)) {
                            error "precompile.sh not found at '${params.PRECOMPILE_SCRIPT_PATH}' in the repository."
                        }
                        sh "chmod +x '${params.PRECOMPILE_SCRIPT_PATH}'"
                    }
                    echo "Signing assets checked out successfully."
                }
            }
        }

        // --------------------------------------------------------
        // 3. EXTRACT + CHECKSUM (pre-signing artifact)
        // --------------------------------------------------------
        stage('Extract & Checksum (Unsigned)') {
            steps {
                script {
                    // Move the uploaded file param into the working dir
                    sh "cp '${INPUT_ARTIFACT_ZIP}' ${WORKDIR}/input/input.zip"

                    echo "Extracting input zip..."
                    sh "unzip -q -o ${WORKDIR}/input/input.zip -d ${WORKDIR}/extracted"

                    if (params.PLATFORM == 'ios') {
                        env.XCARCHIVE_PATH = sh(
                            script: "find ${WORKDIR}/extracted -maxdepth 3 -name '*.xcarchive' -type d | head -1",
                            returnStdout: true
                        ).trim()
                        if (!env.XCARCHIVE_PATH) {
                            error "No .xcarchive found inside the uploaded zip."
                        }
                        echo "Found xcarchive: ${env.XCARCHIVE_PATH}"

                        // Re-zip xcarchive to a canonical name for checksumming/upload
                        def archiveBaseName = sh(
                            script: "basename '${env.XCARCHIVE_PATH}' .xcarchive",
                            returnStdout: true
                        ).trim()
                        env.XCARCHIVE_ZIP = "${WORKDIR}/extracted/${archiveBaseName}.xcarchive.zip"
                        sh """
                            cd "\$(dirname '${env.XCARCHIVE_PATH}')"
                            zip -qr "${env.XCARCHIVE_ZIP}" "\$(basename '${env.XCARCHIVE_PATH}')"
                        """

                        sh """
                            md5    -q '${env.XCARCHIVE_ZIP}' > ${WORKDIR}/checksums/xcarchive.md5
                            shasum -a 1 '${env.XCARCHIVE_ZIP}' | awk '{print \$1}' > ${WORKDIR}/checksums/xcarchive.sha1
                        """
                    } else {
                        env.UNSIGNED_PKG_PATH = sh(
                            script: "find ${WORKDIR}/extracted -maxdepth 3 \\( -name '*.apk' -o -name '*.aab' \\) | head -1",
                            returnStdout: true
                        ).trim()
                        env.MAPPING_PATH = sh(
                            script: "find ${WORKDIR}/extracted -maxdepth 3 -name 'mapping.txt' | head -1",
                            returnStdout: true
                        ).trim()

                        if (!env.UNSIGNED_PKG_PATH) {
                            error "No unsigned .apk/.aab found inside the uploaded zip."
                        }
                        if (!env.MAPPING_PATH) {
                            echo "WARNING: mapping.txt not found in the uploaded zip."
                        }
                        echo "Found unsigned package: ${env.UNSIGNED_PKG_PATH}"
                        echo "Found mapping file    : ${env.MAPPING_PATH ?: '(none)'}"

                        sh """
                            md5    -q '${env.UNSIGNED_PKG_PATH}' > ${WORKDIR}/checksums/unsigned_package.md5
                            shasum -a 1 '${env.UNSIGNED_PKG_PATH}' | awk '{print \$1}' > ${WORKDIR}/checksums/unsigned_package.sha1
                        """
                    }

                    echo "--- Unsigned artifact checksums ---"
                    sh "cat ${WORKDIR}/checksums/*.md5 ${WORKDIR}/checksums/*.sha1 2>/dev/null || true"
                }
            }
        }

        // --------------------------------------------------------
        // 4. SIGN
        //    iOS     -> unlock Keychain, then xcodebuild -exportArchive
        //    Android -> precompile.sh (zipalign + jarsigner / apksigner)
        // --------------------------------------------------------
        stage('Sign Package') {
            steps {
                script {
                    if (params.PLATFORM == 'ios') {
                        echo "Unlocking macOS Keychain for code signing..."
                        withCredentials([string(credentialsId: 'ios-keychain-password', variable: 'KEYCHAIN_PWD')]) {
                            sh '''
                                security unlock-keychain -p "$KEYCHAIN_PWD" "$HOME/Library/Keychains/login.keychain-db"
                                security set-keychain-settings -lut 21600 "$HOME/Library/Keychains/login.keychain-db"
                            '''
                        }

                        if (params.IOS_PROVISIONING_PROFILE?.trim()) {
                            echo "Verifying provisioning profile is installed..."
                            sh """
                                ls "\$HOME/Library/MobileDevice/Provisioning Profiles/" | grep -qi "${params.IOS_PROVISIONING_PROFILE}" \
                                    && echo "Provisioning profile found." \
                                    || echo "WARNING: '${params.IOS_PROVISIONING_PROFILE}' not found in installed profiles — relying on exportOptions.plist resolution."
                            """
                        }

                        echo "Exporting signed IPA from xcarchive..."
                        sh """
                            xcodebuild -exportArchive \
                                -archivePath "${env.XCARCHIVE_PATH}" \
                                -exportOptionsPlist "${params.EXPORT_OPTIONS_PLIST_PATH}" \
                                -exportPath "${WORKDIR}/signed" \
                                -allowProvisioningUpdates \
                                ${params.IOS_SIGNING_IDENTITY ? "-signingCertificate \"${params.IOS_SIGNING_IDENTITY}\"" : ""}
                        """
                        env.SIGNED_ARTIFACT_PATH = sh(
                            script: "find ${WORKDIR}/signed -name '*.ipa' | head -1",
                            returnStdout: true
                        ).trim()
                        if (!env.SIGNED_ARTIFACT_PATH) {
                            error "Export failed: no signed .ipa produced. Check ExportOptions.plist and signing identity."
                        }
                        echo "Signed IPA produced at: ${env.SIGNED_ARTIFACT_PATH}"

                        echo "Verifying code signature..."
                        sh """
                            WORKDIR_TMP=\$(mktemp -d)
                            unzip -q "${env.SIGNED_ARTIFACT_PATH}" -d "\$WORKDIR_TMP"
                            APP_PATH=\$(find "\$WORKDIR_TMP/Payload" -maxdepth 1 -name '*.app' | head -1)
                            codesign --verify --deep --strict --verbose=2 "\$APP_PATH"
                            rm -rf "\$WORKDIR_TMP"
                        """

                    } else {
                        echo "Running precompile.sh to sign/align the Android package..."
                        // precompile.sh is expected to perform zipalign + jarsigner/apksigner
                        // using the keystore credentials and emit the signed artifact path.
                        withCredentials([
                            file(credentialsId: params.ANDROID_KEYSTORE_CRED_ID, variable: 'KEYSTORE_FILE'),
                            string(credentialsId: 'android-keystore-password', variable: 'KEYSTORE_PWD')
                        ]) {
                            sh """
                                export ANDROID_HOME="${ANDROID_HOME}"
                                ./'${params.PRECOMPILE_SCRIPT_PATH}' \
                                    --input "${env.UNSIGNED_PKG_PATH}" \
                                    --output-dir "${WORKDIR}/signed" \
                                    --keystore "\$KEYSTORE_FILE" \
                                    --keystore-password "\$KEYSTORE_PWD" \
                                    --key-alias "${params.ANDROID_KEY_ALIAS}"
                            """
                        }
                        env.SIGNED_ARTIFACT_PATH = sh(
                            script: "find ${WORKDIR}/signed \\( -name '*.apk' -o -name '*.aab' \\) | head -1",
                            returnStdout: true
                        ).trim()
                        if (!env.SIGNED_ARTIFACT_PATH) {
                            error "Signing failed: no signed .apk/.aab produced by precompile.sh."
                        }
                        echo "Signed package produced at: ${env.SIGNED_ARTIFACT_PATH}"

                        echo "Verifying signature with apksigner..."
                        sh '''
                            if [[ "''' + env.SIGNED_ARTIFACT_PATH + '''" == *.apk ]]; then
                                BUILD_TOOLS_DIR=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
                                "${BUILD_TOOLS_DIR}apksigner" verify --verbose "''' + env.SIGNED_ARTIFACT_PATH + '''"
                            fi
                        '''
                    }
                }
            }
        }

        // --------------------------------------------------------
        // 5. CHECKSUM (signed artifact)
        // --------------------------------------------------------
        stage('Checksum (Signed)') {
            steps {
                script {
                    sh """
                        md5    -q '${env.SIGNED_ARTIFACT_PATH}' > ${WORKDIR}/checksums/signed_package.md5
                        shasum -a 1 '${env.SIGNED_ARTIFACT_PATH}' | awk '{print \$1}' > ${WORKDIR}/checksums/signed_package.sha1
                    """
                    echo "--- Signed artifact checksums ---"
                    sh "cat ${WORKDIR}/checksums/signed_package.md5 ${WORKDIR}/checksums/signed_package.sha1"
                }
                archiveArtifacts artifacts: 'work/checksums/*', allowEmptyArchive: true
            }
        }

        // --------------------------------------------------------
        // 6. NEXUS IQ SCAN
        // --------------------------------------------------------
        stage('Nexus IQ Scan') {
            steps {
                script {
                    def scanTarget = params.PLATFORM == 'ios' ? env.SIGNED_ARTIFACT_PATH : env.SIGNED_ARTIFACT_PATH
                    nexusPolicyEvaluation(
                        iqApplication: params.NEXUS_PROJECT_NAME,
                        iqStage      : 'release',
                        scanPatterns : [[scanPattern: scanTarget]],
                        failBuildOnNetworkError: false
                    )
                }
            }
        }

        // --------------------------------------------------------
        // 7. PUBLISH TO JFROG ARTIFACTORY
        //    Uploads: xcarchive / unsigned apk-aab + signed package
        // --------------------------------------------------------
        stage('Publish to Artifactory') {
            steps {
                script {
                    def basePath = params.ARTIFACT_PATH?.trim()
                        ? params.ARTIFACT_PATH
                        : "${params.ARTIFACTORY_REPO}/${params.PLATFORM}/${params.REPO_NAME}/${params.RELEASE_VERSION ?: BUILD_TIMESTAMP}"

                    def server = Artifactory.server('jfrog-artifactory')
                    server.credentialsId = 'jfrog-credentials'

                    def filesSpec = []

                    if (params.PLATFORM == 'ios') {
                        filesSpec << [pattern: env.XCARCHIVE_ZIP, target: "${basePath}/xcarchive/"]
                        filesSpec << [pattern: env.SIGNED_ARTIFACT_PATH, target: "${basePath}/signed-ipa/"]
                    } else {
                        filesSpec << [pattern: env.UNSIGNED_PKG_PATH, target: "${basePath}/unsigned/"]
                        if (env.MAPPING_PATH) {
                            filesSpec << [pattern: env.MAPPING_PATH, target: "${basePath}/unsigned/"]
                        }
                        filesSpec << [pattern: env.SIGNED_ARTIFACT_PATH, target: "${basePath}/signed/"]
                    }

                    def specJson = groovy.json.JsonOutput.toJson([files: filesSpec.collect { f ->
                        [
                            pattern: f.pattern,
                            target : f.target,
                            props  : "platform=${params.PLATFORM};repo=${params.REPO_NAME};branch=${params.BRANCH_NAME};release_version=${params.RELEASE_VERSION ?: ''};build_timestamp=${BUILD_TIMESTAMP}"
                        ]
                    }])

                    echo "Uploading artifacts to: ${basePath}"
                    def buildInfo = server.upload(spec: specJson)
                    server.publishBuildInfo buildInfo

                    // Also upload checksum files for traceability
                    def checksumSpec = groovy.json.JsonOutput.toJson([
                        files: [[pattern: "work/checksums/*", target: "${basePath}/checksums/"]]
                    ])
                    server.upload(spec: checksumSpec)

                    env.ARTIFACTORY_BASE_PATH = basePath
                    echo "All artifacts published successfully to Artifactory under: ${basePath}"
                }
            }
        }

        // --------------------------------------------------------
        // 8. CREATE JIRA TICKET
        // --------------------------------------------------------
        stage('Create Jira Ticket') {
            steps {
                script {
                    def summary = "Signed Release: ${params.REPO_NAME} [${params.PLATFORM}] ${params.RELEASE_VERSION ?: BUILD_TIMESTAMP}"
                    def descriptionText = """Signing pipeline completed for ${params.REPO_NAME}.
Platform: ${params.PLATFORM}
Branch: ${params.BRANCH_NAME}
Release Version: ${params.RELEASE_VERSION ?: '(not specified)'}
Artifactory Path: ${env.ARTIFACTORY_BASE_PATH}
Build URL: ${env.BUILD_URL}
Signed Artifact: ${env.SIGNED_ARTIFACT_PATH?.tokenize('/')?.last()}"""

                    def jiraBody = groovy.json.JsonOutput.toJson([
                        fields: [
                            project    : [key: params.JIRA_PROJECT_KEY],
                            summary    : summary,
                            description: [
                                type: 'doc', version: 1,
                                content: [[
                                    type: 'paragraph',
                                    content: [[type: 'text', text: descriptionText]]
                                ]]
                            ],
                            issuetype  : [name: params.JIRA_ISSUE_TYPE],
                            labels     : ['signed-release', params.PLATFORM, 'automated']
                        ]
                    ])

                    writeFile file: "${WORKDIR}/jira_payload.json", text: jiraBody

                    sh """
                        curl -s -u "\${JIRA_CREDENTIALS_USR}:\${JIRA_CREDENTIALS_PSW}" \
                             -X POST \
                             -H "Content-Type: application/json" \
                             -d @${WORKDIR}/jira_payload.json \
                             "\${JIRA_BASE_URL}/rest/api/3/issue" \
                        | tee ${WORKDIR}/jira_response.json \
                        | python3 -c "import sys, json; r=json.load(sys.stdin); print('Jira ticket created:', r.get('key','unknown'))"
                    """
                }
            }
        }
    }

    /*
    ============================================================
      POST
    ============================================================
    */
    post {
        always {
            echo "=== Post: Re-locking macOS Keychain ==="
            sh '''
                security lock-keychain "$HOME/Library/Keychains/login.keychain-db" || true
            '''

            echo "=== Post: Cleaning workspace ==="
            cleanWs(
                cleanWhenSuccess : true,
                cleanWhenFailure : false,
                cleanWhenAborted : true,
                notFailBuild     : true,
                deleteDirs       : true
            )
        }

        success {
            echo "✅ Signing pipeline completed successfully."
        }

        failure {
            echo "❌ Signing pipeline FAILED. Workspace retained for debugging."
        }
    }
}
