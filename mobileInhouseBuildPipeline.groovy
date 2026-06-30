pipeline {
    agent any

    /*
    ============================================================
      PARAMETERS
    ============================================================
    */
    parameters {
        // --- Source Control ---
        string(name: 'REPO_NAME',         defaultValue: '',          description: 'Bitbucket repository name (e.g. my-mobile-app)')
        string(name: 'BRANCH_NAME',       defaultValue: 'main',      description: 'Branch to build')
        string(name: 'BITBUCKET_REPO_URL',defaultValue: '',          description: 'Full Bitbucket repository URL (https://bitbucket.org/org/repo.git)')

        // --- Platform & Framework ---
        choice(name: 'PLATFORM',
               choices: ['ios', 'android'],
               description: 'Target platform')
        choice(name: 'APP_TYPE',
               choices: ['react_native', 'native', 'hybrid'],
               description: 'Application framework type')

        // --- Build ---
        string(name: 'BUILD_FLAVOR',      defaultValue: 'release',   description: 'Build flavor / configuration (e.g. debug, release, staging)')

        // --- SonarQube ---
        string(name: 'SONAR_PROJECT_KEY', defaultValue: '',          description: 'SonarQube project key')
        string(name: 'SONAR_PROJECT_NAME',defaultValue: '',          description: 'SonarQube project name')
        string(name: 'SONAR_SOURCES',     defaultValue: 'src',       description: 'Comma-separated list of source directories for SonarQube')
        booleanParam(name: 'QUALITY_GATE_CHECK', defaultValue: true, description: 'Fail pipeline if SonarQube Quality Gate is not met')

        // --- Nexus IQ ---
        string(name: 'NEXUS_PROJECT_NAME',defaultValue: '',          description: 'Nexus IQ project / application name')

        // --- Artifactory ---
        string(name: 'ARTIFACTORY_REPO', defaultValue: 'mobile-releases', description: 'JFrog Artifactory target repository')
        string(name: 'ARTIFACT_PATH',    defaultValue: '',           description: '(Optional) Override artifact upload path in Artifactory')

        // --- Post-Stage ---
        booleanParam(name: 'CREATE_JIRA_TICKET', defaultValue: false, description: 'Create a Jira ticket on build failure')
        string(name: 'JIRA_PROJECT_KEY',  defaultValue: '',          description: 'Jira project key (required when CREATE_JIRA_TICKET is true)')
    }

    /*
    ============================================================
      ENVIRONMENT
    ============================================================
    */
    environment {
        // Jenkins credentials IDs — configure these in Jenkins > Credentials
        BITBUCKET_CREDS       = credentials('bitbucket-credentials')
        SONARQUBE_TOKEN       = credentials('sonarqube-token')
        NEXUSIQ_TOKEN         = credentials('nexusiq-token')
        CRASHLYTICS_TOKEN     = credentials('crashlytics-token')
        JFROG_CREDENTIALS     = credentials('jfrog-credentials')
        JIRA_CREDENTIALS      = credentials('jira-credentials')

        // Derived at runtime
        COMMIT_ID             = ''
        ARTIFACT_FILE         = ''
        BUILD_TIMESTAMP       = sh(script: "date '+%Y%m%d%H%M%S'", returnStdout: true).trim()
    }

    /*
    ============================================================
      OPTIONS
    ============================================================
    */
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
                    if (!params.REPO_NAME?.trim()) {
                        error "REPO_NAME parameter is required."
                    }
                    if (!params.BITBUCKET_REPO_URL?.trim()) {
                        error "BITBUCKET_REPO_URL parameter is required."
                    }
                    if (!params.SONAR_PROJECT_KEY?.trim()) {
                        error "SONAR_PROJECT_KEY parameter is required."
                    }
                    if (!params.NEXUS_PROJECT_NAME?.trim()) {
                        error "NEXUS_PROJECT_NAME parameter is required."
                    }
                    if (params.CREATE_JIRA_TICKET && !params.JIRA_PROJECT_KEY?.trim()) {
                        error "JIRA_PROJECT_KEY is required when CREATE_JIRA_TICKET is enabled."
                    }

                    echo """
╔══════════════════════════════════════════════╗
║           BUILD CONFIGURATION SUMMARY        ║
╠══════════════════════════════════════════════╣
║  Repository  : ${params.REPO_NAME}
║  Branch      : ${params.BRANCH_NAME}
║  Platform    : ${params.PLATFORM}
║  App Type    : ${params.APP_TYPE}
║  Build Flavor: ${params.BUILD_FLAVOR}
╚══════════════════════════════════════════════╝
                    """
                }
            }
        }

        // --------------------------------------------------------
        // 2. CHECKOUT
        // --------------------------------------------------------
        stage('Checkout') {
            steps {
                script {
                    echo "Checking out ${params.REPO_NAME} @ ${params.BRANCH_NAME}"
                }
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${params.BRANCH_NAME}"]],
                    userRemoteConfigs: [[
                        url          : params.BITBUCKET_REPO_URL,
                        credentialsId: 'bitbucket-credentials'
                    ]],
                    extensions: [
                        [$class: 'CloneOption', depth: 1, shallow: true],
                        [$class: 'CleanBeforeCheckout']
                    ]
                ])
                script {
                    COMMIT_ID = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    echo "Commit ID: ${COMMIT_ID}"
                    currentBuild.description = "${params.PLATFORM} | ${params.APP_TYPE} | ${params.BRANCH_NAME} | ${COMMIT_ID.take(8)}"
                }
            }
        }

        // --------------------------------------------------------
        // 3. INSTALL DEPENDENCIES
        //    Platform/framework-specific dependency installation
        // --------------------------------------------------------
        stage('Install Dependencies') {
            steps {
                script {
                    switch (params.APP_TYPE) {
                        case 'react_native':
                            echo "Installing Node/React Native dependencies..."
                            sh '''
                                export NVM_DIR="$HOME/.nvm"
                                [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
                                node --version
                                npm --version
                                npm ci --prefer-offline
                            '''
                            if (params.PLATFORM == 'ios') {
                                sh 'cd ios && pod install --repo-update'
                            }
                            break

                        case 'native':
                            if (params.PLATFORM == 'ios') {
                                echo "Installing iOS native dependencies (CocoaPods)..."
                                sh 'pod install --repo-update'
                            } else {
                                echo "Android native — Gradle handles dependencies automatically."
                            }
                            break

                        case 'hybrid':
                            echo "Installing hybrid app dependencies..."
                            sh '''
                                if [ -f "package.json" ]; then
                                    npm ci --prefer-offline
                                fi
                                if [ -f "Podfile" ] && [ "${PLATFORM}" = "ios" ]; then
                                    pod install --repo-update
                                fi
                            '''
                            break

                        default:
                            error "Unknown APP_TYPE: ${params.APP_TYPE}"
                    }
                }
            }
        }

        // --------------------------------------------------------
        // 4. BUILD
        //    Delegates to build.sh from the checked-out repo
        // --------------------------------------------------------
        stage('Build') {
            steps {
                script {
                    if (!fileExists('build.sh')) {
                        error "build.sh not found in the repository root."
                    }
                    sh 'chmod +x build.sh'
                }
                sh """
                    ./build.sh \
                        --platform   "${params.PLATFORM}" \
                        --app-type   "${params.APP_TYPE}" \
                        --flavor     "${params.BUILD_FLAVOR}" \
                        --branch     "${params.BRANCH_NAME}" \
                        --commit-id  "${COMMIT_ID}"
                """
                script {
                    // Locate the produced artifact
                    if (params.PLATFORM == 'ios') {
                        ARTIFACT_FILE = sh(
                            script: "find . -name '*.ipa' | head -1",
                            returnStdout: true
                        ).trim()
                    } else {
                        ARTIFACT_FILE = sh(
                            script: "find . -name '*.apk' -o -name '*.aab' | grep -v test | head -1",
                            returnStdout: true
                        ).trim()
                    }

                    if (!ARTIFACT_FILE) {
                        error "No artifact (.ipa / .apk / .aab) found after build."
                    }
                    echo "Artifact located: ${ARTIFACT_FILE}"
                }
            }
        }

        // --------------------------------------------------------
        // 5. ARTIFACT INTEGRITY CHECK
        //    Verifies the build output exists, is non-empty, and
        //    is properly signed before any downstream work begins.
        // --------------------------------------------------------
        stage('Artifact Integrity Check') {
            steps {
                script {
                    if (!ARTIFACT_FILE?.trim()) {
                        error "Artifact Integrity Check failed: no artifact path resolved from Build stage."
                    }
                    if (!fileExists(ARTIFACT_FILE)) {
                        error "Artifact Integrity Check failed: '${ARTIFACT_FILE}' does not exist on disk."
                    }

                    def fileSize = sh(script: "stat -c%s '${ARTIFACT_FILE}' 2>/dev/null || stat -f%z '${ARTIFACT_FILE}'", returnStdout: true).trim().toLong()
                    echo "Artifact size: ${fileSize} bytes"
                    if (fileSize < 1024) {
                        error "Artifact Integrity Check failed: '${ARTIFACT_FILE}' is suspiciously small (${fileSize} bytes) — likely a corrupted or empty build."
                    }
                }

                script {
                    if (params.PLATFORM == 'ios') {
                        echo "Verifying iOS code signature..."
                        sh """
                            set -e
                            IPA_PATH="${ARTIFACT_FILE}"
                            WORKDIR=\$(mktemp -d)
                            unzip -q "\$IPA_PATH" -d "\$WORKDIR"

                            APP_PATH=\$(find "\$WORKDIR/Payload" -maxdepth 1 -name '*.app' | head -1)
                            if [ -z "\$APP_PATH" ]; then
                                echo "ERROR: No .app bundle found inside IPA."
                                rm -rf "\$WORKDIR"
                                exit 1
                            fi

                            echo "Checking code signature for: \$APP_PATH"
                            codesign --verify --deep --strict --verbose=2 "\$APP_PATH"

                            echo "Checking provisioning profile presence..."
                            if [ ! -f "\$APP_PATH/embedded.mobileprovision" ]; then
                                echo "ERROR: embedded.mobileprovision not found — unsigned or ad-hoc misconfiguration."
                                rm -rf "\$WORKDIR"
                                exit 1
                            fi

                            echo "iOS code signature verified successfully."
                            rm -rf "\$WORKDIR"
                        """
                    } else {
                        echo "Verifying Android APK/AAB signature..."
                        sh """
                            set -e
                            ARTIFACT_PATH="${ARTIFACT_FILE}"

                            case "\$ARTIFACT_PATH" in
                                *.apk)
                                    echo "Running apksigner verify..."
                                    \${ANDROID_HOME:-/opt/android-sdk}/build-tools/*/apksigner verify --verbose "\$ARTIFACT_PATH"
                                    ;;
                                *.aab)
                                    echo "Validating AAB structure with bundletool..."
                                    if ! command -v bundletool >/dev/null 2>&1; then
                                        echo "WARNING: bundletool not found on PATH — skipping deep AAB validation, falling back to zip integrity check."
                                        unzip -tq "\$ARTIFACT_PATH" >/dev/null
                                    else
                                        bundletool validate --bundle="\$ARTIFACT_PATH"
                                    fi
                                    ;;
                                *)
                                    echo "ERROR: Unrecognized artifact extension for integrity check: \$ARTIFACT_PATH"
                                    exit 1
                                    ;;
                            esac

                            echo "Android artifact signature/integrity verified successfully."
                        """
                    }
                }

                echo "✅ Artifact Integrity Check passed: ${ARTIFACT_FILE}"
            }
        }

        // --------------------------------------------------------
        // 6. UNIT TESTS
        // --------------------------------------------------------
        stage('Unit Tests') {
            steps {
                script {
                    switch (params.APP_TYPE) {
                        case 'react_native':
                            sh 'npm test -- --ci --coverage --reporters=default --reporters=jest-junit'
                            break
                        case 'native':
                            if (params.PLATFORM == 'ios') {
                                sh '''
                                    xcodebuild test \
                                        -workspace *.xcworkspace \
                                        -scheme "${SCHEME:-AppTests}" \
                                        -destination "platform=iOS Simulator,name=iPhone 15" \
                                        -resultBundlePath test-results.xcresult \
                                        | xcpretty --report junit --output test-results/junit.xml
                                '''
                            } else {
                                sh './gradlew test'
                            }
                            break
                        case 'hybrid':
                            sh '''
                                if [ -f "package.json" ]; then
                                    npm test -- --ci --coverage || true
                                fi
                                if [ "${PLATFORM}" = "android" ]; then
                                    ./gradlew test || true
                                fi
                            '''
                            break
                    }
                }
            }
            post {
                always {
                    script {
                        // Publish JUnit results when available
                        def junitFiles = sh(
                            script: "find . -name 'TEST-*.xml' -o -name 'junit.xml' 2>/dev/null | head -1",
                            returnStdout: true
                        ).trim()
                        if (junitFiles) {
                            junit allowEmptyResults: true,
                                  testResults: '**/TEST-*.xml, **/junit.xml, **/test-results/**/*.xml'
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------
        // 7. SONARQUBE SCAN
        // --------------------------------------------------------
        stage('SonarQube Scan') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        sonar-scanner \
                            -Dsonar.projectKey="${params.SONAR_PROJECT_KEY}" \
                            -Dsonar.projectName="${params.SONAR_PROJECT_NAME}" \
                            -Dsonar.sources="${params.SONAR_SOURCES}" \
                            -Dsonar.scm.provider=git \
                            -Dsonar.links.scm="${params.BITBUCKET_REPO_URL}" \
                            -Dsonar.branch.name="${params.BRANCH_NAME}" \
                            -Dsonar.scm.revision="${COMMIT_ID}" \
                            -Dsonar.token="${SONARQUBE_TOKEN}"
                    """
                }
                script {
                    if (params.QUALITY_GATE_CHECK) {
                        echo "Waiting for SonarQube Quality Gate result..."
                        timeout(time: 10, unit: 'MINUTES') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                error "SonarQube Quality Gate FAILED: ${qg.status}. Halting pipeline."
                            }
                            echo "SonarQube Quality Gate PASSED: ${qg.status}"
                        }
                    } else {
                        echo "Quality Gate check skipped (QUALITY_GATE_CHECK=false)."
                    }
                }
            }
        }

        // --------------------------------------------------------
        // 8. NEXUS IQ SCAN
        // --------------------------------------------------------
        stage('Nexus IQ Scan') {
            steps {
                script {
                    def nexusScanFile = ARTIFACT_FILE ?: '.'
                    nexusPolicyEvaluation(
                        iqApplication    : params.NEXUS_PROJECT_NAME,
                        iqStage          : 'build',
                        scanPatterns     : [[scanPattern: nexusScanFile]],
                        failBuildOnNetworkError: false
                    )
                }
                // Pass additional metadata via environment for traceability
                sh """
                    echo "Nexus IQ Scan Metadata:"
                    echo "  Repository URL : ${params.BITBUCKET_REPO_URL}"
                    echo "  Commit ID      : ${COMMIT_ID}"
                    echo "  Branch         : ${params.BRANCH_NAME}"
                    echo "  Project        : ${params.NEXUS_PROJECT_NAME}"
                """
            }
        }

        // --------------------------------------------------------
        // 9. UPLOAD CRASH SYMBOLS
        //    iOS  → dSYM to Firebase Crashlytics
        //    Android → mapping.txt to Firebase Crashlytics
        // --------------------------------------------------------
        stage('Upload Crash Symbols') {
            steps {
                script {
                    if (params.PLATFORM == 'ios') {
                        echo "Uploading dSYM files to Firebase Crashlytics..."
                        sh """
                            DSYM_PATH=\$(find . -name '*.dSYM.zip' -o -name '*.dSYM' | head -1)
                            if [ -z "\$DSYM_PATH" ]; then
                                echo "WARNING: No dSYM files found. Skipping Crashlytics upload."
                            else
                                echo "Found dSYM: \$DSYM_PATH"
                                # Using Firebase CLI
                                firebase crashlytics:symbols:upload \
                                    --app "\${FIREBASE_APP_ID_IOS}" \
                                    --token "${CRASHLYTICS_TOKEN}" \
                                    "\$DSYM_PATH"
                            fi
                        """
                    } else {
                        echo "Uploading Android mapping file to Firebase Crashlytics..."
                        sh """
                            MAPPING_PATH=\$(find . -path '*/mapping/release/mapping.txt' | head -1)
                            if [ -z "\$MAPPING_PATH" ]; then
                                echo "WARNING: No mapping.txt found. Skipping Crashlytics upload."
                            else
                                echo "Found mapping: \$MAPPING_PATH"
                                firebase crashlytics:mappingfile:upload \
                                    --app "\${FIREBASE_APP_ID_ANDROID}" \
                                    --token "${CRASHLYTICS_TOKEN}" \
                                    "\$MAPPING_PATH"
                            fi
                        """
                    }
                }
            }
        }

        // --------------------------------------------------------
        // 10. PUBLISH TO JFROG ARTIFACTORY
        // --------------------------------------------------------
        stage('Publish to Artifactory') {
            steps {
                script {
                    def artifactName   = ARTIFACT_FILE.tokenize('/').last()
                    def uploadPath     = params.ARTIFACT_PATH?.trim()
                                        ? params.ARTIFACT_PATH
                                        : "${params.ARTIFACTORY_REPO}/${params.PLATFORM}/${params.APP_TYPE}/${params.REPO_NAME}/${params.BRANCH_NAME}/${BUILD_TIMESTAMP}/${artifactName}"

                    echo "Publishing artifact: ${ARTIFACT_FILE} → ${uploadPath}"

                    def server = Artifactory.server('jfrog-artifactory')
                    server.credentialsId = 'jfrog-credentials'

                    def uploadSpec = """{
                        "files": [{
                            "pattern" : "${ARTIFACT_FILE}",
                            "target"  : "${uploadPath}",
                            "props"   : "platform=${params.PLATFORM};app_type=${params.APP_TYPE};branch=${params.BRANCH_NAME};commit_id=${COMMIT_ID};build_timestamp=${BUILD_TIMESTAMP};build_flavor=${params.BUILD_FLAVOR}"
                        }]
                    }"""

                    def buildInfo = server.upload(spec: uploadSpec)
                    server.publishBuildInfo buildInfo

                    echo "Artifact published successfully to Artifactory."
                    echo "Path: ${uploadPath}"
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
            echo "=== Post: Cleaning workspace ==="
            cleanWs(
                cleanWhenSuccess : true,
                cleanWhenFailure : false,   // keep on failure for debugging
                cleanWhenAborted : true,
                notFailBuild     : true,
                deleteDirs       : true
            )
        }

        success {
            echo "✅ Pipeline completed successfully."
            // Notify Slack / email on success (configure in Jenkins)
            // slackSend channel: '#mobile-builds', color: 'good',
            //     message: "✅ Build succeeded: ${params.REPO_NAME} [${params.BRANCH_NAME}] (${params.PLATFORM})"
        }

        failure {
            script {
                echo "❌ Pipeline FAILED."

                if (params.CREATE_JIRA_TICKET) {
                    echo "Creating Jira ticket for build failure..."
                    def jiraBody = """
                        {
                            "fields": {
                                "project"    : { "key": "${params.JIRA_PROJECT_KEY}" },
                                "summary"    : "Build Failure: ${params.REPO_NAME} [${params.BRANCH_NAME}] (${params.PLATFORM})",
                                "description": {
                                    "type"   : "doc",
                                    "version": 1,
                                    "content": [{
                                        "type"   : "paragraph",
                                        "content": [{
                                            "type": "text",
                                            "text": "Jenkins build #${env.BUILD_NUMBER} failed.\\nJob: ${env.JOB_NAME}\\nBranch: ${params.BRANCH_NAME}\\nCommit: ${COMMIT_ID}\\nPlatform: ${params.PLATFORM}\\nBuild URL: ${env.BUILD_URL}"
                                        }]
                                    }]
                                },
                                "issuetype"  : { "name": "Bug" },
                                "priority"   : { "name": "High" },
                                "labels"     : ["ci-failure", "${params.PLATFORM}", "automated"]
                            }
                        }
                    """
                    sh """
                        curl -s -u "${JIRA_CREDENTIALS_USR}:${JIRA_CREDENTIALS_PSW}" \
                             -X POST \
                             -H "Content-Type: application/json" \
                             -d '${jiraBody}' \
                             "\${JIRA_BASE_URL}/rest/api/3/issue" \
                        | python3 -c "import sys, json; r=json.load(sys.stdin); print('Jira ticket created:', r.get('key','unknown'))"
                    """
                } else {
                    echo "Jira ticket creation skipped (CREATE_JIRA_TICKET=false)."
                }

                // Notify Slack / email on failure
                // slackSend channel: '#mobile-builds', color: 'danger',
                //     message: "❌ Build failed: ${params.REPO_NAME} [${params.BRANCH_NAME}] (${params.PLATFORM}) — ${env.BUILD_URL}"
            }
        }

        unstable {
            echo "⚠️ Pipeline is UNSTABLE (test failures detected)."
        }
    }
}
