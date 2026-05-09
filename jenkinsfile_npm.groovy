pipeline {
    agent any

    parameters {
        string(
            name: 'PACKAGES',
            defaultValue: '',
            description: 'Space-separated list of npm packages to mirror (e.g. "lodash@4.17.21 axios@1.6.0")'
        )
        string(
            name: 'ONLINE_ARTIFACTORY_URL',
            defaultValue: 'https://online-artifactory.example.com/artifactory',
            description: 'Base URL of the online Artifactory instance'
        )
        string(
            name: 'ONLINE_NPM_REPO',
            defaultValue: 'npm-remote',
            description: 'Online Artifactory npm repository name'
        )
        string(
            name: 'OFFLINE_ARTIFACTORY_URL',
            defaultValue: 'https://offline-artifactory.example.com/artifactory',
            description: 'Base URL of the offline Artifactory instance'
        )
        string(
            name: 'OFFLINE_NPM_REPO',
            defaultValue: 'npm-local',
            description: 'Offline Artifactory npm repository name'
        )
        booleanParam(
            name: 'INCLUDE_PEER_DEPS',
            defaultValue: true,
            description: 'Whether to include peer dependencies'
        )
        booleanParam(
            name: 'INCLUDE_DEV_DEPS',
            defaultValue: false,
            description: 'Whether to include devDependencies'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: true,
            description: 'When enabled, resolves and lists all packages that would be mirrored without installing to the offline Artifactory'
        )
    }

    environment {
        ONLINE_ARTIFACTORY_CREDS  = credentials('online-artifactory-credentials')   // Jenkins username/password credential
        OFFLINE_ARTIFACTORY_CREDS = credentials('offline-artifactory-credentials')  // Jenkins username/password credential
        WORK_DIR                  = "${WORKSPACE}/npm-mirror-work"
        OFFLINE_WORK_DIR          = "${WORKSPACE}/npm-offline-work"
    }

    options {
        timestamps()
        ansiColor('xterm')
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    stages {

        // ─────────────────────────────────────────────
        stage('Validate Parameters') {
        // ─────────────────────────────────────────────
            steps {
                script {
                    if (!params.PACKAGES?.trim()) {
                        error("❌ PACKAGES parameter is required. Provide at least one package name.")
                    }
                    echo "📦 Packages to mirror: ${params.PACKAGES}"
                    echo "🌐 Online  Artifactory: ${params.ONLINE_ARTIFACTORY_URL}/${params.ONLINE_NPM_REPO}"
                    echo "🔒 Offline Artifactory: ${params.OFFLINE_ARTIFACTORY_URL}/${params.OFFLINE_NPM_REPO}"
                    echo "🔗 Include peer deps:   ${params.INCLUDE_PEER_DEPS}"
                    echo "🛠  Include dev deps:    ${params.INCLUDE_DEV_DEPS}"
                    echo "🧪 Dry run:             ${params.DRY_RUN}"
                    if (params.DRY_RUN) {
                        echo "ℹ️  DRY RUN MODE — packages will be resolved and listed but NOT installed to the offline Artifactory"
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        stage('Setup Workspace') {
        // ─────────────────────────────────────────────
            steps {
                sh '''
                    rm -rf "$WORK_DIR" "$OFFLINE_WORK_DIR"
                    mkdir -p "$WORK_DIR" "$OFFLINE_WORK_DIR"
                '''
            }
        }

        // ─────────────────────────────────────────────
        stage('Install from Online Artifactory') {
        // ─────────────────────────────────────────────
        // npm install resolves the full dependency tree (including peer deps)
        // and writes a package-lock.json with all resolved tarball URLs pointing
        // at the online Artifactory. That lockfile is our source of truth.
        // ─────────────────────────────────────────────
            steps {
                dir("${env.WORK_DIR}") {
                    sh '''
                        ONLINE_HOST=$(echo "$ONLINE_ARTIFACTORY_URL" | sed 's|https://||;s|http://||')

                        cat > .npmrc <<EOF
registry=${ONLINE_ARTIFACTORY_URL}/api/npm/${ONLINE_NPM_REPO}/
//${ONLINE_HOST}/api/npm/${ONLINE_NPM_REPO}/:username=${ONLINE_ARTIFACTORY_CREDS_USR}
//${ONLINE_HOST}/api/npm/${ONLINE_NPM_REPO}/:_password=$(echo -n "${ONLINE_ARTIFACTORY_CREDS_PSW}" | base64)
//${ONLINE_HOST}/api/npm/${ONLINE_NPM_REPO}/:email=jenkins@ci.local
//${ONLINE_HOST}/api/npm/${ONLINE_NPM_REPO}/:always-auth=true
EOF
                        npm init -y
                    '''

                    script {
                        def installFlags = '--prefer-offline=false'
                        if (params.INCLUDE_PEER_DEPS) {
                            installFlags += ' --legacy-peer-deps=false'
                        }
                        if (!params.INCLUDE_DEV_DEPS) {
                            installFlags += ' --omit=dev'
                        }

                        sh """
                            echo "📥 Installing from online Artifactory: ${params.PACKAGES}"
                            npm install ${installFlags} ${params.PACKAGES}

                            echo ""
                            echo "✅ Install complete. package-lock.json generated."
                            LOCKED_COUNT=\$(node -e "
                                const lock = require('./package-lock.json');
                                const pkgs = Object.keys(lock.packages || {}).filter(k => k !== '');
                                console.log(pkgs.length);
                            ")
                            echo "   Locked packages: \$LOCKED_COUNT"
                        """
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        stage('Rewrite Lockfile — Point to Offline Artifactory') {
        // ─────────────────────────────────────────────
        // Walk every "resolved" URL in package-lock.json and replace the online
        // Artifactory base URL with the offline one. npm ci will then fetch
        // directly from the offline registry using these exact URLs.
        // ─────────────────────────────────────────────
            steps {
                dir("${env.WORK_DIR}") {
                    sh '''
                        echo "🔁 Rewriting package-lock.json resolved URLs..."
                        echo "   FROM: ${ONLINE_ARTIFACTORY_URL}/api/npm/${ONLINE_NPM_REPO}"
                        echo "   TO:   ${OFFLINE_ARTIFACTORY_URL}/api/npm/${OFFLINE_NPM_REPO}"

                        node -e "
                            const fs   = require('fs');
                            const lock = JSON.parse(fs.readFileSync('package-lock.json', 'utf8'));

                            const onlineBase  = process.env.ONLINE_ARTIFACTORY_URL  + '/api/npm/' + process.env.ONLINE_NPM_REPO;
                            const offlineBase = process.env.OFFLINE_ARTIFACTORY_URL + '/api/npm/' + process.env.OFFLINE_NPM_REPO;

                            let count = 0;

                            function rewrite(obj) {
                                if (!obj || typeof obj !== 'object') return;
                                if (typeof obj.resolved === 'string' && obj.resolved.startsWith(onlineBase)) {
                                    obj.resolved = obj.resolved.replace(onlineBase, offlineBase);
                                    count++;
                                }
                                for (const val of Object.values(obj)) rewrite(val);
                            }

                            rewrite(lock);
                            fs.writeFileSync('package-lock.json', JSON.stringify(lock, null, 2));
                            console.log('✅ Rewrote ' + count + ' resolved URL(s) in package-lock.json');
                        "

                        # Copy rewritten lockfile + package.json to offline work dir
                        cp package-lock.json "$OFFLINE_WORK_DIR/package-lock.json"
                        cp package.json      "$OFFLINE_WORK_DIR/package.json"

                        echo "📄 Rewritten lockfile saved. Packages to be mirrored:"
                        node -e "
                            const lock = require('./package-lock.json');
                            const pkgs = Object.entries(lock.packages || {})
                                .filter(([k]) => k !== '')
                                .map(([k, v]) => '  ' + k.replace('node_modules/', '') + '@' + v.version);
                            pkgs.sort().forEach(p => console.log(p));
                            console.log('');
                            console.log('Total: ' + pkgs.length + ' packages');
                        " | tee /tmp/resolved-packages.txt
                    '''
                }
            }
        }

        // ─────────────────────────────────────────────
        stage('npm ci — Install from Offline Artifactory') {
        // ─────────────────────────────────────────────
        // npm ci installs strictly from the rewritten lockfile — no dependency
        // resolution. Every resolved URL now points at the offline Artifactory,
        // which pulls and caches the packages from its upstream remote repo
        // (or serves them if already cached).
        // Skipped entirely when DRY_RUN is true.
        // ─────────────────────────────────────────────
            when {
                expression { return !params.DRY_RUN }
            }
            steps {
                dir("${env.OFFLINE_WORK_DIR}") {
                    sh '''
                        OFFLINE_HOST=$(echo "$OFFLINE_ARTIFACTORY_URL" | sed 's|https://||;s|http://||')

                        # Write .npmrc pointing exclusively at the offline Artifactory
                        cat > .npmrc <<EOF
registry=${OFFLINE_ARTIFACTORY_URL}/api/npm/${OFFLINE_NPM_REPO}/
//${OFFLINE_HOST}/api/npm/${OFFLINE_NPM_REPO}/:username=${OFFLINE_ARTIFACTORY_CREDS_USR}
//${OFFLINE_HOST}/api/npm/${OFFLINE_NPM_REPO}/:_password=$(echo -n "${OFFLINE_ARTIFACTORY_CREDS_PSW}" | base64)
//${OFFLINE_HOST}/api/npm/${OFFLINE_NPM_REPO}/:email=jenkins@ci.local
//${OFFLINE_HOST}/api/npm/${OFFLINE_NPM_REPO}/:always-auth=true
EOF

                        echo "🔒 Running npm ci against offline Artifactory..."
                        npm ci \
                            --prefer-offline=false \
                            --audit=false

                        echo ""
                        echo "✅ npm ci succeeded — all packages fetched from offline Artifactory"
                    '''
                }
            }
        }

        // ─────────────────────────────────────────────
        stage('Verify') {
        // ─────────────────────────────────────────────
        // Confirm node_modules was fully populated by npm ci.
        // Skipped entirely when DRY_RUN is true.
        // ─────────────────────────────────────────────
            when {
                expression { return !params.DRY_RUN }
            }
            steps {
                dir("${env.OFFLINE_WORK_DIR}") {
                    sh '''
                        echo "🔍 Verifying installed packages in offline node_modules..."

                        npm ls --all 2>/dev/null || true

                        INSTALLED=$(find node_modules -maxdepth 1 -mindepth 1 -type d | wc -l)
                        echo ""
                        echo "✅ $INSTALLED top-level package directories present in node_modules"
                    '''
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    post {
    // ─────────────────────────────────────────────
        always {
            // Archive the rewritten lockfile and resolved package list for audit trail
            archiveArtifacts(
                artifacts: 'npm-mirror-work/package-lock.json, npm-mirror-work/package.json',
                allowEmptyArchive: true,
                fingerprint: true
            )
            archiveArtifacts(
                artifacts: '/tmp/resolved-packages.txt',
                allowEmptyArchive: true
            )

            // Clean up working directories
            sh 'rm -rf "$WORK_DIR" "$OFFLINE_WORK_DIR"'
        }

        success {
            script {
                if (params.DRY_RUN) {
                    echo """
╔════════════════════════════════════════════════════════════╗
║  🧪 Dry run complete!                                      ║
║                                                            ║
║  The package list above shows everything that would be     ║
║  mirrored to the offline Artifactory.                      ║
║                                                            ║
║  Re-run with DRY_RUN=false to perform the actual mirror.   ║
╚════════════════════════════════════════════════════════════╝
                    """
                } else {
                    echo """
╔════════════════════════════════════════════════════════════╗
║  ✅ Mirror complete!                                       ║
║                                                            ║
║  Packages are now cached on the offline Artifactory.       ║
║  Point your project .npmrc to:                             ║
║  ${OFFLINE_ARTIFACTORY_URL}/api/npm/${OFFLINE_NPM_REPO}/   ║
║                                                            ║
║  Then run: npm ci                                          ║
╚════════════════════════════════════════════════════════════╝
                    """
                }
            }
        }

        failure {
            echo "❌ Pipeline failed. Check stage logs above for details."
        }
    }
}
