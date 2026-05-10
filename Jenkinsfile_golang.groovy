pipeline {
    agent any

    environment {
        // ── Online Artifactory (internet-connected) ──────────────────────────
        ONLINE_ARTIFACTORY_URL  = 'https://online-artifactory.example.com/artifactory'
        ONLINE_GO_REPO          = 'go-virtual'
        ONLINE_CREDS_ID         = 'online-artifactory-credentials'   // Jenkins credential ID

        // ── Air-Gapped Local Artifactory ─────────────────────────────────────
        AIRGAP_ARTIFACTORY_URL  = 'https://airgap-artifactory.local/artifactory'
        AIRGAP_GO_REPO          = 'go-local'
        AIRGAP_CREDS_ID         = 'airgap-artifactory-credentials'   // Jenkins credential ID

        // ── Go environment ───────────────────────────────────────────────────
        GOVERSION               = '1.22'
        GOPATH                  = "${WORKSPACE}/go-cache"
        GOPROXY                 = "${ONLINE_ARTIFACTORY_URL}/api/go/${ONLINE_GO_REPO}"
        GONOSUMDB               = '*'
        GOFLAGS                 = '-mod=mod'

        // ── Working directories ──────────────────────────────────────────────
        MODULE_CACHE_DIR        = "${WORKSPACE}/go-cache/pkg/mod/cache/download"
        EXPORT_DIR              = "${WORKSPACE}/module-export"
        MIRROR_SCRIPT           = "${WORKSPACE}/scripts/upload-modules.sh"
    }

    options {
        timestamps()
        ansiColor('xterm')
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
    }

    parameters {
        string(
            name: 'GO_MODULE',
            defaultValue: '',
            description: 'Specific module to mirror, e.g. github.com/some/package@v1.2.3. Leave blank to mirror ALL modules in go.sum.'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'If true, download modules but skip uploading to air-gapped Artifactory.'
        )
        booleanParam(
            name: 'VERIFY_UPLOAD',
            defaultValue: true,
            description: 'If true, verify each file is reachable in air-gapped Artifactory after upload.'
        )
    }

    stages {

        // ── Stage 1 ──────────────────────────────────────────────────────────
        stage('Prepare Environment') {
            steps {
                echo '==> Preparing workspace and Go environment'
                sh '''
                    set -e
                    mkdir -p "${GOPATH}" "${EXPORT_DIR}" "$(dirname ${MIRROR_SCRIPT})"

                    # Verify Go is available
                    go version || { echo "[ERROR] Go not found on PATH"; exit 1; }

                    # Print effective Go env
                    go env GOPATH GOPROXY GONOSUMDB GOFLAGS
                '''
            }
        }

        // ── Stage 2 ──────────────────────────────────────────────────────────
        stage('Validate go.sum / Module Input') {
            steps {
                echo '==> Validating module source'
                sh '''
                    set -e
                    if [ -n "${GO_MODULE}" ]; then
                        echo "[INFO] Single-module mode: ${GO_MODULE}"
                    elif [ -f go.sum ]; then
                        echo "[INFO] Bulk-mirror mode: found go.sum with $(wc -l < go.sum) entries"
                    else
                        echo "[ERROR] No GO_MODULE specified and no go.sum found in workspace root."
                        exit 1
                    fi
                '''
            }
        }

        // ── Stage 3 ──────────────────────────────────────────────────────────
        stage('Download Modules from Online Artifactory') {
            steps {
                echo '==> Downloading Go modules from online Artifactory'
                withCredentials([usernamePassword(
                    credentialsId: env.ONLINE_CREDS_ID,
                    usernameVariable: 'ART_USER',
                    passwordVariable: 'ART_PASS'
                )]) {
                    sh '''
                        set -e

                        # Embed credentials in GOPROXY so go toolchain can authenticate
                        export GOPROXY="https://${ART_USER}:${ART_PASS}@${ONLINE_ARTIFACTORY_URL#https://}/api/go/${ONLINE_GO_REPO}"

                        if [ -n "${GO_MODULE}" ]; then
                            # ── Single module ────────────────────────────────
                            echo "[INFO] Downloading module: ${GO_MODULE}"
                            GOPATH="${GOPATH}" go mod download "${GO_MODULE}"
                        else
                            # ── All modules listed in go.sum ─────────────────
                            echo "[INFO] Running: go mod download (all dependencies)"
                            GOPATH="${GOPATH}" go mod download -x
                        fi

                        echo "[INFO] Download complete. Cache contents:"
                        find "${MODULE_CACHE_DIR}" -type f | sort
                    '''
                }
            }
        }

        // ── Stage 4 ──────────────────────────────────────────────────────────
        stage('Inventory Downloaded Artifacts') {
            steps {
                echo '==> Inventorying downloaded module files'
                sh '''
                    set -e
                    TOTAL=$(find "${MODULE_CACHE_DIR}" -type f \
                        \( -name "*.zip" -o -name "*.mod" -o -name "*.info" \) | wc -l)
                    echo "[INFO] Total files to transfer: ${TOTAL}"

                    echo "[INFO] Breakdown by type:"
                    find "${MODULE_CACHE_DIR}" -name "*.zip"  | wc -l | xargs printf "  .zip  : %s files\n"
                    find "${MODULE_CACHE_DIR}" -name "*.mod"  | wc -l | xargs printf "  .mod  : %s files\n"
                    find "${MODULE_CACHE_DIR}" -name "*.info" | wc -l | xargs printf "  .info : %s files\n"

                    # Write manifest for audit trail
                    find "${MODULE_CACHE_DIR}" -type f \
                        \( -name "*.zip" -o -name "*.mod" -o -name "*.info" \) \
                        | sort > "${EXPORT_DIR}/manifest.txt"
                    echo "[INFO] Manifest saved to ${EXPORT_DIR}/manifest.txt"
                '''
            }
        }

        // ── Stage 5 ──────────────────────────────────────────────────────────
        stage('Generate Upload Script') {
            steps {
                echo '==> Generating upload script for air-gapped Artifactory'
                sh '''
                    set -e
                    cat > "${MIRROR_SCRIPT}" <<'SCRIPT'
#!/usr/bin/env bash
# Auto-generated by Jenkins — do not edit manually
set -euo pipefail

AIRGAP_URL="${1}"
AIRGAP_REPO="${2}"
MODULE_CACHE="${3}"
ART_USER="${4}"
ART_PASS="${5}"
VERIFY="${6:-false}"

PASS=0
FAIL=0

upload_file() {
    local file="${1}"
    local rel_path="${file#${MODULE_CACHE}/}"
    local target_url="${AIRGAP_URL}/${AIRGAP_REPO}/${rel_path}"

    echo "[UPLOAD] ${rel_path}"
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -u "${ART_USER}:${ART_PASS}" \
        -T "${file}" \
        "${target_url}")

    if [[ "${HTTP_STATUS}" =~ ^(200|201)$ ]]; then
        PASS=$((PASS + 1))
        if [ "${VERIFY}" = "true" ]; then
            VERIFY_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
                -u "${ART_USER}:${ART_PASS}" \
                "${target_url}")
            if [[ "${VERIFY_STATUS}" =~ ^(200|201)$ ]]; then
                echo "  [OK]       Verified: ${rel_path}"
            else
                echo "  [WARN]     Verify failed (HTTP ${VERIFY_STATUS}): ${rel_path}"
            fi
        fi
    else
        echo "  [FAILED]   HTTP ${HTTP_STATUS}: ${rel_path}"
        FAIL=$((FAIL + 1))
    fi
}

export -f upload_file

find "${MODULE_CACHE}" -type f \
    \( -name "*.zip" -o -name "*.mod" -o -name "*.info" \) \
    | sort \
    | while read -r f; do upload_file "${f}"; done

echo ""
echo "=========================================="
echo "  Upload Summary"
echo "  Passed : ${PASS}"
echo "  Failed : ${FAIL}"
echo "=========================================="

[ "${FAIL}" -eq 0 ] || exit 1
SCRIPT
                    chmod +x "${MIRROR_SCRIPT}"
                    echo "[INFO] Upload script generated at ${MIRROR_SCRIPT}"
                '''
            }
        }

        // ── Stage 6 ──────────────────────────────────────────────────────────
        stage('Upload Modules to Air-Gapped Artifactory') {
            when {
                expression { return !params.DRY_RUN }
            }
            steps {
                echo '==> Uploading modules to air-gapped Artifactory'
                withCredentials([usernamePassword(
                    credentialsId: env.AIRGAP_CREDS_ID,
                    usernameVariable: 'AIRGAP_USER',
                    passwordVariable: 'AIRGAP_PASS'
                )]) {
                    sh '''
                        set -e
                        "${MIRROR_SCRIPT}" \
                            "${AIRGAP_ARTIFACTORY_URL}" \
                            "${AIRGAP_GO_REPO}" \
                            "${MODULE_CACHE_DIR}" \
                            "${AIRGAP_USER}" \
                            "${AIRGAP_PASS}" \
                            "${VERIFY_UPLOAD}"
                    '''
                }
            }
        }

        // ── Stage 6 (dry-run path) ────────────────────────────────────────────
        stage('Dry Run — Skip Upload') {
            when {
                expression { return params.DRY_RUN }
            }
            steps {
                echo '==> DRY RUN enabled — skipping upload to air-gapped Artifactory'
                sh '''
                    echo "[DRY RUN] Files that would be uploaded:"
                    cat "${EXPORT_DIR}/manifest.txt"
                '''
            }
        }

        // ── Stage 7 ──────────────────────────────────────────────────────────
        stage('Smoke Test — Resolve from Air-Gapped Artifactory') {
            when {
                allOf {
                    expression { return !params.DRY_RUN }
                    expression { return params.VERIFY_UPLOAD }
                }
            }
            steps {
                echo '==> Smoke-testing module resolution from air-gapped Artifactory'
                withCredentials([usernamePassword(
                    credentialsId: env.AIRGAP_CREDS_ID,
                    usernameVariable: 'AIRGAP_USER',
                    passwordVariable: 'AIRGAP_PASS'
                )]) {
                    sh '''
                        set -e

                        # Use a clean GOPATH so we resolve from air-gapped only
                        CLEAN_GOPATH="${WORKSPACE}/go-verify"
                        mkdir -p "${CLEAN_GOPATH}"

                        export GOPROXY="https://${AIRGAP_USER}:${AIRGAP_PASS}@${AIRGAP_ARTIFACTORY_URL#https://}/api/go/${AIRGAP_GO_REPO},off"
                        export GONOSUMDB="*"
                        export GOPATH="${CLEAN_GOPATH}"

                        if [ -n "${GO_MODULE}" ]; then
                            echo "[INFO] Verifying single module: ${GO_MODULE}"
                            go mod download "${GO_MODULE}" && echo "[PASS] Module resolved from air-gapped Artifactory"
                        else
                            echo "[INFO] Verifying all modules via go mod download"
                            go mod download && echo "[PASS] All modules resolved from air-gapped Artifactory"
                        fi
                    '''
                }
            }
        }
    }

    // ── Post actions ─────────────────────────────────────────────────────────
    post {
        always {
            echo '==> Archiving manifest and pipeline artifacts'
            archiveArtifacts artifacts: 'module-export/manifest.txt', allowEmptyArchive: true

            script {
                def status = currentBuild.result ?: 'SUCCESS'
                def icon   = status == 'SUCCESS' ? '✅' : '❌'
                echo "${icon} Build finished with status: ${status}"
            }
        }

        success {
            echo '✅ All Go modules successfully mirrored to air-gapped Artifactory.'
        }

        failure {
            echo '❌ Pipeline failed. Check stage logs above for details.'
            // Optionally notify via email/Slack here
        }

        cleanup {
            echo '==> Cleaning up temporary Go cache'
            sh 'rm -rf "${WORKSPACE}/go-cache" "${WORKSPACE}/go-verify" || true'
        }
    }
}
