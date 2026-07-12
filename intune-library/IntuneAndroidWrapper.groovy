package org.example.intune

/**
 * Wraps an Android app using the Microsoft Intune App Wrapping Tool for
 * Android (Invoke-AppWrappingTool, a PowerShell cmdlet). This tool only
 * runs on Windows, so the pipeline calling this class must already be
 * executing on a Windows agent.
 *
 * Accepts either a .apk or a .aab as input:
 *   - .apk is passed straight to the wrapping tool.
 *   - .aab is not supported by the Intune wrapping tool directly, so it is
 *     first converted to a universal .apk with Google's `bundletool`
 *     (build-apks --mode=universal), then that generated .apk is wrapped.
 *
 * Underlying cmdlet (per Microsoft docs):
 *   Import-Module "<toolInstallDir>\IntuneAppWrappingTool.psm1"
 *   Invoke-AppWrappingTool -InputPath <in.apk> -OutputPath <out.apk> `
 *       [-KeyStorePath <path> -KeyAlias <alias> -SigAlg <alg> `
 *        -KeyStorePassword <SecureString> -KeyPassword <SecureString>] [-Verbose]
 *
 * Re-signing (KeyStorePath/KeyAlias/passwords) is optional: if the input
 * .apk is already signed the tool can wrap it without those parameters.
 *
 * Underlying bundletool command (per Google docs), used only for .aab input:
 *   java -jar bundletool.jar build-apks --bundle=<in.aab> --output=<out.apks> \
 *       --mode=universal [--ks=<path> --ks-key-alias=<alias> --ks-pass=file:<pwd> --key-pass=file:<pwd>]
 *   The resulting .apks is a zip containing a single "universal.apk".
 */
class IntuneAndroidWrapper implements Serializable {

    private static final String DEFAULT_TOOL_INSTALL_DIR =
        'C:\\Program Files (x86)\\Microsoft Intune Mobile Application Management\\Android\\App Wrapping Tool'
    private static final String DEFAULT_SIG_ALG = 'SHA256withRSA'

    private final def script
    private final String toolInstallDir

    IntuneAndroidWrapper(script, String toolInstallDir = null) {
        this.script = script
        this.toolInstallDir = toolInstallDir ?: DEFAULT_TOOL_INSTALL_DIR
    }

    /**
     * @param config map with keys:
     *   inputApk (required)                     - path to source .apk OR .aab
     *   outputDir (required)                     - directory to place the wrapped .apk
     *   bundletoolPath (required if input is .aab)
     *                                             - path to bundletool's .jar, used to convert
     *                                               the .aab to a universal .apk before wrapping
     *   bundleKeyStorePath (optional)            - keystore used to sign the interim universal
     *                                               .apk produced from an .aab. Defaults to
     *                                               keyStorePath if not set. If neither is set,
     *                                               bundletool falls back to its own default
     *                                               debug keystore (or produces an unsigned APK
     *                                               if none exists).
     *   bundleKeyAlias (optional)                - defaults to keyAlias
     *   bundleKeyStorePasswordCredentialsId (optional) - defaults to keyStorePasswordCredentialsId
     *   bundleKeyPasswordCredentialsId (optional)      - defaults to keyPasswordCredentialsId
     *   keyStorePath (optional)                  - keystore to re-sign the final wrapped app with;
     *                                               omit if the input is already signed and you
     *                                               don't need the wrapper to re-sign it
     *   keyAlias (required if keyStorePath set)  - signing key alias
     *   keyStorePasswordCredentialsId (required if keyStorePath set)
     *                                             - Jenkins "Secret text" credential ID
     *   keyPasswordCredentialsId (optional)      - defaults to keyStorePasswordCredentialsId
     *   sigAlg (optional)                        - default SHA256withRSA
     *   outputFileName (optional)                - defaults to "<input>-wrapped.apk"
     *   verbose (optional boolean)
     * @return absolute path to the wrapped .apk
     */
    String wrap(Map config) {
        validate(config)

        String inputApp = config.inputApk
        String outputDir = config.outputDir
        boolean verbose = config.verbose as boolean
        boolean resign = config.keyStorePath as boolean
        boolean isBundle = isAab(inputApp)

        assertOnWindows()
        assertPathExists(inputApp, isBundle ? 'Input AAB' : 'Input APK')
        assertToolExists()

        script.powershell(script: "New-Item -ItemType Directory -Force -Path \"${outputDir}\" | Out-Null",
            label: 'Ensure output directory exists')

        String baseName = inputApp.tokenize('\\/').last().replaceAll(/(?i)\.(apk|aab)$/, '')
        String defaultOutputName = "${baseName}-wrapped.apk"
        String outputFileName = config.outputFileName ?: defaultOutputName
        String outputApk = joinPath(outputDir, outputFileName)

        String workDir = null
        try {
            String apkToWrap = inputApp
            if (isBundle) {
                workDir = createWorkDir()
                apkToWrap = convertAabToApk(config, inputApp, workDir)
            }

            script.echo "[intuneWrapAndroid] Wrapping ${baseName} -> ${outputApk}"

            if (resign) {
                wrapWithSigning(config, apkToWrap, outputApk, verbose)
            } else {
                wrapWithoutSigning(apkToWrap, outputApk, verbose)
            }
        } finally {
            if (workDir) {
                cleanupWorkDir(workDir)
            }
        }

        int existsStatus = script.powershell(
            script: "if (Test-Path \"${outputApk}\") { exit 0 } else { exit 1 }",
            returnStatus: true
        )
        if (existsStatus != 0) {
            throw new IntuneWrapperException("Wrapping reported success but the expected output file was not found: ${outputApk}")
        }

        script.echo "[intuneWrapAndroid] Successfully wrapped APK: ${outputApk}"
        return outputApk
    }

    // ---- AAB -> universal APK conversion (bundletool) ----------------------

    private boolean isAab(String path) {
        return path ==~ /(?i).+\.aab$/
    }

    private String createWorkDir() {
        String workDir = script.powershell(
            script: '[System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "intune-wrap-" + [System.Guid]::NewGuid().ToString())',
            returnStdout: true
        ).trim()
        script.powershell(script: "New-Item -ItemType Directory -Force -Path \"${workDir}\" | Out-Null",
            label: 'Create AAB conversion working directory')
        return workDir
    }

    private void cleanupWorkDir(String workDir) {
        script.powershell(script: "Remove-Item -Recurse -Force -ErrorAction SilentlyContinue \"${workDir}\"",
            label: 'Clean up AAB conversion working directory')
    }

    private String convertAabToApk(Map config, String aabPath, String workDir) {
        String bundletoolPath = config.bundletoolPath
        if (!bundletoolPath) {
            throw new IntuneWrapperException(
                "inputApk is an .aab file — the 'bundletoolPath' parameter (path to bundletool's .jar) " +
                "is required to convert an App Bundle to a .apk before it can be wrapped."
            )
        }
        assertPathExists(bundletoolPath, 'bundletool.jar')

        String apksPath = "${workDir}\\universal.apks"
        String extractDir = "${workDir}\\extracted"

        runBundletoolBuildApks(config, bundletoolPath, aabPath, apksPath, workDir)

        String zipPath = "${workDir}\\universal.zip"
        String extractScript = """
\$ErrorActionPreference = 'Stop'
Copy-Item -Path "${apksPath}" -Destination "${zipPath}" -Force
Expand-Archive -Path "${zipPath}" -DestinationPath "${extractDir}" -Force
exit \$LASTEXITCODE
"""
        int extractStatus = script.powershell(script: extractScript, returnStatus: true, label: 'Extract universal APK from bundletool output')
        if (extractStatus != 0) {
            throw new IntuneWrapperException("Failed to extract the universal APK from bundletool's .apks output (exit code ${extractStatus}).")
        }

        String universalApk = "${extractDir}\\universal.apk"
        assertPathExists(universalApk, 'Universal APK produced by bundletool')
        script.echo "[intuneWrapAndroid] Converted AAB to universal APK: ${universalApk}"
        return universalApk
    }

    private void runBundletoolBuildApks(Map config, String bundletoolPath, String aabPath, String apksPath, String workDir) {
        String ksPath = config.bundleKeyStorePath ?: config.keyStorePath
        String ksAlias = config.bundleKeyAlias ?: config.keyAlias
        String ksPwdCredId = config.bundleKeyStorePasswordCredentialsId ?: config.keyStorePasswordCredentialsId
        String keyPwdCredId = config.bundleKeyPasswordCredentialsId ?: config.keyPasswordCredentialsId ?: ksPwdCredId

        if (ksPath && (!ksAlias || !ksPwdCredId)) {
            throw new IntuneWrapperException(
                "When signing the AAB->APK conversion with a keystore, 'bundleKeyAlias' (or 'keyAlias') and " +
                "'bundleKeyStorePasswordCredentialsId' (or 'keyStorePasswordCredentialsId') are also required."
            )
        }

        if (!ksPath) {
            String ps = """
\$ErrorActionPreference = 'Stop'
& java -jar "${bundletoolPath}" build-apks --bundle="${aabPath}" --output="${apksPath}" --mode=universal --overwrite
exit \$LASTEXITCODE
"""
            int status = script.powershell(script: ps, returnStatus: true, label: 'Convert AAB to universal APK (bundletool)')
            if (status != 0) {
                throw new IntuneWrapperException(
                    "bundletool build-apks failed with exit code ${status} while converting the AAB. " +
                    "If this is a signing error, supply 'bundleKeyStorePath' / 'bundleKeyAlias' / " +
                    "'bundleKeyStorePasswordCredentialsId' (or reuse keyStorePath/keyAlias/keyStorePasswordCredentialsId) " +
                    "so bundletool can sign the interim APK."
                )
            }
            return
        }

        assertPathExists(ksPath, 'AAB signing keystore')

        script.withCredentials([
            script.string(credentialsId: ksPwdCredId, variable: 'INTUNE_BT_KS_PWD'),
            script.string(credentialsId: keyPwdCredId, variable: 'INTUNE_BT_KEY_PWD')
        ]) {
            String ksPwdFile = "${workDir}\\bt_ks.pwd"
            String keyPwdFile = "${workDir}\\bt_key.pwd"
            String ps = """
\$ErrorActionPreference = 'Stop'
Set-Content -Path "${ksPwdFile}" -Value \$env:INTUNE_BT_KS_PWD -NoNewline
Set-Content -Path "${keyPwdFile}" -Value \$env:INTUNE_BT_KEY_PWD -NoNewline
try {
    & java -jar "${bundletoolPath}" build-apks --bundle="${aabPath}" --output="${apksPath}" --mode=universal --overwrite --ks="${ksPath}" --ks-key-alias="${ksAlias}" --ks-pass=file:"${ksPwdFile}" --key-pass=file:"${keyPwdFile}"
    exit \$LASTEXITCODE
} finally {
    Remove-Item -Force -ErrorAction SilentlyContinue "${ksPwdFile}", "${keyPwdFile}"
}
"""
            int status = script.powershell(script: ps, returnStatus: true, label: 'Convert AAB to universal APK (bundletool, signed)')
            if (status != 0) {
                throw new IntuneWrapperException("bundletool build-apks failed with exit code ${status} while converting the AAB.")
            }
        }
    }

    // ---- Intune wrapping -----------------------------------------------------

    private void wrapWithoutSigning(String inputApk, String outputApk, boolean verbose) {
        String verboseFlag = verbose ? '-Verbose' : ''
        String ps = """
\$ErrorActionPreference = 'Stop'
Import-Module "${toolInstallDir}\\IntuneAppWrappingTool.psm1" -Force
Invoke-AppWrappingTool -InputPath "${inputApk}" -OutputPath "${outputApk}" ${verboseFlag}
exit \$LASTEXITCODE
"""
        int status = script.powershell(script: ps, returnStatus: true, label: 'Run Invoke-AppWrappingTool')
        if (status != 0) {
            throw new IntuneWrapperException("Invoke-AppWrappingTool failed with exit code ${status}. Check the console log above for the tool's error output.")
        }
    }

    private void wrapWithSigning(Map config, String inputApk, String outputApk, boolean verbose) {
        String keyStorePath = config.keyStorePath
        String keyAlias = config.keyAlias
        String sigAlg = config.sigAlg ?: DEFAULT_SIG_ALG
        String ksPwdCredId = config.keyStorePasswordCredentialsId
        String keyPwdCredId = config.keyPasswordCredentialsId ?: ksPwdCredId

        if (!keyAlias || !ksPwdCredId) {
            throw new IntuneWrapperException(
                "When 'keyStorePath' is provided, 'keyAlias' and 'keyStorePasswordCredentialsId' are also required."
            )
        }
        assertPathExists(keyStorePath, 'Keystore')

        String verboseFlag = verbose ? '-Verbose' : ''

        script.withCredentials([
            script.string(credentialsId: ksPwdCredId, variable: 'INTUNE_KS_PWD'),
            script.string(credentialsId: keyPwdCredId, variable: 'INTUNE_KEY_PWD')
        ]) {
            String ps = """
\$ErrorActionPreference = 'Stop'
Import-Module "${toolInstallDir}\\IntuneAppWrappingTool.psm1" -Force
\$ksPwd = ConvertTo-SecureString -String \$env:INTUNE_KS_PWD -AsPlainText -Force
\$keyPwd = ConvertTo-SecureString -String \$env:INTUNE_KEY_PWD -AsPlainText -Force
Invoke-AppWrappingTool -InputPath "${inputApk}" -OutputPath "${outputApk}" -KeyStorePath "${keyStorePath}" -KeyAlias "${keyAlias}" -SigAlg "${sigAlg}" -KeyStorePassword \$ksPwd -KeyPassword \$keyPwd ${verboseFlag}
exit \$LASTEXITCODE
"""
            int status = script.powershell(script: ps, returnStatus: true, label: 'Run Invoke-AppWrappingTool (with signing)')
            if (status != 0) {
                throw new IntuneWrapperException("Invoke-AppWrappingTool failed with exit code ${status}. Check the console log above for the tool's error output.")
            }
        }
    }

    // ---- validation & helpers -------------------------------------------------

    private void validate(Map config) {
        List<String> required = ['inputApk', 'outputDir']
        List<String> missing = required.findAll { !config[it] }
        if (missing) {
            throw new IntuneWrapperException("intuneWrapAndroid is missing required parameter(s): ${missing.join(', ')}")
        }
        if (!(config.inputApk ==~ /(?i).+\.(apk|aab)$/)) {
            throw new IntuneWrapperException("inputApk must point to a .apk or .aab file, got: ${config.inputApk}")
        }
    }

    private void assertOnWindows() {
        if (script.isUnix()) {
            throw new IntuneWrapperException(
                "intuneWrapAndroid must run on a Windows agent because Invoke-AppWrappingTool is a Windows-only PowerShell module."
            )
        }
    }

    private void assertPathExists(String path, String label) {
        int status = script.powershell(script: "if (Test-Path \"${path}\") { exit 0 } else { exit 1 }", returnStatus: true)
        if (status != 0) {
            throw new IntuneWrapperException("${label} not found at: ${path}")
        }
    }

    private void assertToolExists() {
        String modulePath = "${toolInstallDir}\\IntuneAppWrappingTool.psm1"
        int status = script.powershell(script: "if (Test-Path \"${modulePath}\") { exit 0 } else { exit 1 }", returnStatus: true)
        if (status != 0) {
            throw new IntuneWrapperException(
                "IntuneAppWrappingTool.psm1 not found at: ${modulePath}. " +
                "Override the install location with the 'toolInstallDir' parameter if it was installed elsewhere."
            )
        }
    }

    private String joinPath(String dir, String file) {
        String normalizedDir = dir.endsWith('\\') || dir.endsWith('/') ? dir[0..-2] : dir
        return "${normalizedDir}\\${file}"
    }
}
