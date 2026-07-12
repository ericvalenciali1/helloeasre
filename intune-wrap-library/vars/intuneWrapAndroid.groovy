/**
 * intuneWrapAndroid
 * ------------------
 * Wraps an Android app using the Microsoft Intune App Wrapping Tool for
 * Android (Invoke-AppWrappingTool). Must run where a Windows agent is
 * available, since the tool is a Windows-only PowerShell module.
 *
 * Accepts either a .apk or a .aab as inputApk:
 *   - .apk is wrapped directly.
 *   - .aab is first converted to a universal .apk with Google's bundletool
 *     (the Intune wrapping tool only accepts .apk input), then that is
 *     wrapped. This requires 'bundletoolPath' and a JRE on the agent (the
 *     Intune tool itself already requires Java, so this is normally
 *     already present).
 *
 * Underlying cmdlet (per Microsoft docs):
 *   Import-Module "<toolInstallDir>\IntuneAppWrappingTool.psm1"
 *   Invoke-AppWrappingTool -InputPath <in.apk> -OutputPath <out.apk> `
 *       [-KeyStorePath <path> -KeyAlias <alias> -SigAlg <alg> `
 *        -KeyStorePassword <SecureString> -KeyPassword <SecureString>] [-Verbose]
 *
 * Underlying bundletool command (per Google docs), used only for .aab input:
 *   java -jar bundletool.jar build-apks --bundle=<in.aab> --output=<out.apks> \
 *       --mode=universal [--ks=<path> --ks-key-alias=<alias> --ks-pass=file:<pwd> --key-pass=file:<pwd>]
 *   The resulting .apks is a zip containing a single "universal.apk".
 *
 * Required parameters:
 *   inputApk    Path to the source .apk or .aab to wrap
 *   outputDir   Directory where the wrapped .apk will be placed
 *
 * Required only when inputApk is a .aab:
 *   bundletoolPath   Path to bundletool's .jar, used to build a universal
 *                    .apk from the app bundle before wrapping
 *
 * Optional parameters for signing the interim universal .apk generated
 * from an .aab (only relevant for .aab input — ignored for .apk input).
 * If omitted, bundletool falls back to its own default debug keystore, or
 * produces an unsigned interim APK if none exists on the agent. Since the
 * Intune wrapping tool discards any existing signing on its input anyway,
 * these normally don't need to match your release signing identity:
 *   bundleKeyStorePath                     Keystore to sign the interim APK with
 *                                           (defaults to keyStorePath below if unset)
 *   bundleKeyAlias                         Defaults to keyAlias below
 *   bundleKeyStorePasswordCredentialsId    Defaults to keyStorePasswordCredentialsId below
 *   bundleKeyPasswordCredentialsId         Defaults to keyPasswordCredentialsId below
 *
 * Optional parameters (only needed to re-sign the final wrapped output —
 * omit all of them if you don't want the wrapper to re-sign it):
 *   keyStorePath                    Path to the Java keystore (.jks/.keystore)
 *   keyAlias                        Alias of the signing key (required if keyStorePath set)
 *   keyStorePasswordCredentialsId   Jenkins "Secret text" credential ID holding
 *                                   the keystore password (required if keyStorePath set)
 *   keyPasswordCredentialsId        Jenkins "Secret text" credential ID holding the
 *                                   key password (defaults to keyStorePasswordCredentialsId)
 *   sigAlg                          Signature algorithm (default: SHA256withRSA)
 *
 * Other optional parameters:
 *   toolInstallDir   Install directory of the App Wrapping Tool
 *                    (default: C:\Program Files (x86)\Microsoft Intune Mobile
 *                     Application Management\Android\App Wrapping Tool)
 *   outputFileName   Name for the wrapped .apk (default: "<input>-wrapped.apk")
 *   verbose          Pass -Verbose to the tool (default: false)
 *   agentLabel       If set, this step allocates its own `node(agentLabel) { }` block.
 *                     Leave unset if the enclosing stage already runs on a Windows agent
 *                     (e.g. `agent { label 'windows' }` in a declarative stage).
 *
 * Returns: the absolute path (String) of the wrapped .apk.
 *
 * Example — wrapping an already-signed .apk (scripted, step manages its own node):
 *   def wrappedApk = intuneWrapAndroid(
 *       agentLabel: 'windows',
 *       inputApk: 'C:\\builds\\MyApp.apk',
 *       outputDir: 'C:\\builds\\wrapped',
 *       keyStorePath: 'C:\\keys\\myapp.keystore',
 *       keyAlias: 'myapp',
 *       keyStorePasswordCredentialsId: 'android-keystore-password',
 *       keyPasswordCredentialsId: 'android-key-password'
 *   )
 *
 * Example — wrapping a .aab (declarative, stage already pins the agent):
 *   stage('Wrap Android') {
 *       agent { label 'windows' }
 *       steps {
 *           script {
 *               intuneWrapAndroid(
 *                   inputApk: "${WORKSPACE}\\build\\MyApp.aab",
 *                   outputDir: "${WORKSPACE}\\wrapped",
 *                   bundletoolPath: 'C:\\tools\\bundletool-all.jar',
 *                   keyStorePath: 'C:\\keys\\myapp.keystore',
 *                   keyAlias: 'myapp',
 *                   keyStorePasswordCredentialsId: 'android-keystore-password'
 *               )
 *           }
 *       }
 *   }
 */
def call(Map config = [:]) {
    if (config.agentLabel) {
        node(config.agentLabel) {
            return runWrap(config)
        }
    }
    return runWrap(config)
}

def runWrap(Map config) {
    validateConfig(config)

    String inputApp = config.inputApk
    String outputDir = config.outputDir
    String toolInstallDir = config.toolInstallDir ?:
        'C:\\Program Files (x86)\\Microsoft Intune Mobile Application Management\\Android\\App Wrapping Tool'
    boolean verbose = config.verbose as boolean
    boolean resign = config.keyStorePath as boolean
    boolean isBundle = isAab(inputApp)

    assertOnWindows()
    assertPathExists(inputApp, isBundle ? 'Input AAB' : 'Input APK')
    assertToolExists(toolInstallDir)

    powershell(script: "New-Item -ItemType Directory -Force -Path \"${outputDir}\" | Out-Null",
        label: 'Ensure output directory exists')

    String baseName = inputApp.tokenize('\\/').last().replaceAll(/(?i)\.(apk|aab)$/, '')
    String defaultOutputName = "${baseName}-wrapped.apk"
    String outputFileName = config.outputFileName ?: defaultOutputName
    String outputApk = joinWindowsPath(outputDir, outputFileName)

    String workDir = null
    try {
        String apkToWrap = inputApp
        if (isBundle) {
            workDir = createWorkDir()
            apkToWrap = convertAabToApk(config, inputApp, workDir)
        }

        echo "[intuneWrapAndroid] Wrapping ${baseName} -> ${outputApk}"

        if (resign) {
            wrapWithSigning(config, toolInstallDir, apkToWrap, outputApk, verbose)
        } else {
            wrapWithoutSigning(toolInstallDir, apkToWrap, outputApk, verbose)
        }
    } finally {
        if (workDir) {
            cleanupWorkDir(workDir)
        }
    }

    int existsStatus = powershell(
        script: "if (Test-Path \"${outputApk}\") { exit 0 } else { exit 1 }",
        returnStatus: true
    )
    if (existsStatus != 0) {
        error "Wrapping reported success but the expected output file was not found: ${outputApk}"
    }

    echo "[intuneWrapAndroid] Successfully wrapped APK: ${outputApk}"
    return outputApk
}

// ---- AAB -> universal APK conversion (bundletool) ----------------------

def isAab(String path) {
    return path ==~ /(?i).+\.aab$/
}

def createWorkDir() {
    String workDir = powershell(
        script: '[System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "intune-wrap-" + [System.Guid]::NewGuid().ToString())',
        returnStdout: true
    ).trim()
    powershell(script: "New-Item -ItemType Directory -Force -Path \"${workDir}\" | Out-Null",
        label: 'Create AAB conversion working directory')
    return workDir
}

def cleanupWorkDir(String workDir) {
    powershell(script: "Remove-Item -Recurse -Force -ErrorAction SilentlyContinue \"${workDir}\"",
        label: 'Clean up AAB conversion working directory')
}

def convertAabToApk(Map config, String aabPath, String workDir) {
    String bundletoolPath = config.bundletoolPath
    if (!bundletoolPath) {
        error "inputApk is an .aab file — the 'bundletoolPath' parameter (path to bundletool's .jar) is required to convert an App Bundle to a .apk before it can be wrapped."
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
    int extractStatus = powershell(script: extractScript, returnStatus: true, label: 'Extract universal APK from bundletool output')
    if (extractStatus != 0) {
        error "Failed to extract the universal APK from bundletool's .apks output (exit code ${extractStatus})."
    }

    String universalApk = "${extractDir}\\universal.apk"
    assertPathExists(universalApk, 'Universal APK produced by bundletool')
    echo "[intuneWrapAndroid] Converted AAB to universal APK: ${universalApk}"
    return universalApk
}

def runBundletoolBuildApks(Map config, String bundletoolPath, String aabPath, String apksPath, String workDir) {
    String ksPath = config.bundleKeyStorePath ?: config.keyStorePath
    String ksAlias = config.bundleKeyAlias ?: config.keyAlias
    String ksPwdCredId = config.bundleKeyStorePasswordCredentialsId ?: config.keyStorePasswordCredentialsId
    String keyPwdCredId = config.bundleKeyPasswordCredentialsId ?: config.keyPasswordCredentialsId ?: ksPwdCredId

    if (ksPath && (!ksAlias || !ksPwdCredId)) {
        error "When signing the AAB->APK conversion with a keystore, 'bundleKeyAlias' (or 'keyAlias') and 'bundleKeyStorePasswordCredentialsId' (or 'keyStorePasswordCredentialsId') are also required."
    }

    if (!ksPath) {
        String ps = """
\$ErrorActionPreference = 'Stop'
& java -jar "${bundletoolPath}" build-apks --bundle="${aabPath}" --output="${apksPath}" --mode=universal --overwrite
exit \$LASTEXITCODE
"""
        int status = powershell(script: ps, returnStatus: true, label: 'Convert AAB to universal APK (bundletool)')
        if (status != 0) {
            error "bundletool build-apks failed with exit code ${status} while converting the AAB. If this is a signing error, supply 'bundleKeyStorePath' / 'bundleKeyAlias' / 'bundleKeyStorePasswordCredentialsId' (or reuse keyStorePath/keyAlias/keyStorePasswordCredentialsId) so bundletool can sign the interim APK."
        }
        return
    }

    assertPathExists(ksPath, 'AAB signing keystore')

    withCredentials([
        string(credentialsId: ksPwdCredId, variable: 'INTUNE_BT_KS_PWD'),
        string(credentialsId: keyPwdCredId, variable: 'INTUNE_BT_KEY_PWD')
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
        int status = powershell(script: ps, returnStatus: true, label: 'Convert AAB to universal APK (bundletool, signed)')
        if (status != 0) {
            error "bundletool build-apks failed with exit code ${status} while converting the AAB."
        }
    }
}

// ---- Intune wrapping -----------------------------------------------------

def wrapWithoutSigning(String toolInstallDir, String inputApk, String outputApk, boolean verbose) {
    String verboseFlag = verbose ? '-Verbose' : ''
    String ps = """
\$ErrorActionPreference = 'Stop'
Import-Module "${toolInstallDir}\\IntuneAppWrappingTool.psm1" -Force
Invoke-AppWrappingTool -InputPath "${inputApk}" -OutputPath "${outputApk}" ${verboseFlag}
exit \$LASTEXITCODE
"""
    int status = powershell(script: ps, returnStatus: true, label: 'Run Invoke-AppWrappingTool')
    if (status != 0) {
        error "Invoke-AppWrappingTool failed with exit code ${status}. Check the console log above for the tool's error output."
    }
}

def wrapWithSigning(Map config, String toolInstallDir, String inputApk, String outputApk, boolean verbose) {
    String keyStorePath = config.keyStorePath
    String keyAlias = config.keyAlias
    String sigAlg = config.sigAlg ?: 'SHA256withRSA'
    String ksPwdCredId = config.keyStorePasswordCredentialsId
    String keyPwdCredId = config.keyPasswordCredentialsId ?: ksPwdCredId

    if (!keyAlias || !ksPwdCredId) {
        error "When 'keyStorePath' is provided, 'keyAlias' and 'keyStorePasswordCredentialsId' are also required."
    }
    assertPathExists(keyStorePath, 'Keystore')

    String verboseFlag = verbose ? '-Verbose' : ''

    withCredentials([
        string(credentialsId: ksPwdCredId, variable: 'INTUNE_KS_PWD'),
        string(credentialsId: keyPwdCredId, variable: 'INTUNE_KEY_PWD')
    ]) {
        String ps = """
\$ErrorActionPreference = 'Stop'
Import-Module "${toolInstallDir}\\IntuneAppWrappingTool.psm1" -Force
\$ksPwd = ConvertTo-SecureString -String \$env:INTUNE_KS_PWD -AsPlainText -Force
\$keyPwd = ConvertTo-SecureString -String \$env:INTUNE_KEY_PWD -AsPlainText -Force
Invoke-AppWrappingTool -InputPath "${inputApk}" -OutputPath "${outputApk}" -KeyStorePath "${keyStorePath}" -KeyAlias "${keyAlias}" -SigAlg "${sigAlg}" -KeyStorePassword \$ksPwd -KeyPassword \$keyPwd ${verboseFlag}
exit \$LASTEXITCODE
"""
        int status = powershell(script: ps, returnStatus: true, label: 'Run Invoke-AppWrappingTool (with signing)')
        if (status != 0) {
            error "Invoke-AppWrappingTool failed with exit code ${status}. Check the console log above for the tool's error output."
        }
    }
}

// ---- validation & helpers -------------------------------------------------

def validateConfig(Map config) {
    List<String> required = ['inputApk', 'outputDir']
    List<String> missing = required.findAll { !config[it] }
    if (missing) {
        error "intuneWrapAndroid is missing required parameter(s): ${missing.join(', ')}"
    }
    if (!(config.inputApk ==~ /(?i).+\.(apk|aab)$/)) {
        error "inputApk must point to a .apk or .aab file, got: ${config.inputApk}"
    }
}

def assertOnWindows() {
    if (isUnix()) {
        error "intuneWrapAndroid must run on a Windows agent because Invoke-AppWrappingTool is a Windows-only PowerShell module."
    }
}

def assertPathExists(String path, String label) {
    int status = powershell(script: "if (Test-Path \"${path}\") { exit 0 } else { exit 1 }", returnStatus: true)
    if (status != 0) {
        error "${label} not found at: ${path}"
    }
}

def assertToolExists(String toolInstallDir) {
    String modulePath = "${toolInstallDir}\\IntuneAppWrappingTool.psm1"
    int status = powershell(script: "if (Test-Path \"${modulePath}\") { exit 0 } else { exit 1 }", returnStatus: true)
    if (status != 0) {
        error "IntuneAppWrappingTool.psm1 not found at: ${modulePath}. Override the install location with the 'toolInstallDir' parameter if it was installed elsewhere."
    }
}

def joinWindowsPath(String dir, String file) {
    String normalizedDir = dir.endsWith('\\') || dir.endsWith('/') ? dir[0..-2] : dir
    return "${normalizedDir}\\${file}"
}
