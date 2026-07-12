/**
 * intuneWrapIOS
 * -------------
 * Wraps an .ipa using the Microsoft Intune App Wrapping Tool for iOS
 * (IntuneMAMPackager). Must run where a macOS agent is available, since
 * the tool is a macOS-only binary.
 *
 * Underlying CLI (per Microsoft docs):
 *   IntuneMAMPackager -i <input.ipa> -o <output.ipa> -p <mobileprovision> -c <cert SHA1 or name> [-v]
 *
 * Required parameters:
 *   inputIpa             Path to the source .ipa to wrap
 *   outputDir             Directory where the wrapped .ipa will be placed
 *   provisioningProfile   Path to the .mobileprovision file
 *   signingCertificate    Code signing certificate common name OR SHA1 thumbprint
 *
 * Optional parameters:
 *   toolPath        Path to the IntuneMAMPackager binary
 *                   (default: /Applications/IntuneMAMPackager/Contents/MacOS/IntuneMAMPackager)
 *   outputFileName  Name for the wrapped .ipa (default: "<input>-wrapped.ipa")
 *   verbose         Pass -v to the tool (default: false)
 *   agentLabel      If set, this step allocates its own `node(agentLabel) { }` block.
 *                   Leave unset if the enclosing stage already runs on a macOS agent
 *                   (e.g. `agent { label 'macos' }` in a declarative stage).
 *
 * Returns: the absolute path (String) of the wrapped .ipa.
 *
 * Example (scripted, step manages its own node):
 *   def wrappedIpa = intuneWrapIOS(
 *       agentLabel: 'macos',
 *       inputIpa: '/Users/ci/builds/MyApp.ipa',
 *       outputDir: '/Users/ci/builds/wrapped',
 *       provisioningProfile: '/Users/ci/profiles/MyApp.mobileprovision',
 *       signingCertificate: '1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B'
 *   )
 *
 * Example (declarative, stage already pins the agent):
 *   stage('Wrap iOS') {
 *       agent { label 'macos' }
 *       steps {
 *           script {
 *               intuneWrapIOS(
 *                   inputIpa: "${WORKSPACE}/build/MyApp.ipa",
 *                   outputDir: "${WORKSPACE}/wrapped",
 *                   provisioningProfile: "${WORKSPACE}/profiles/MyApp.mobileprovision",
 *                   signingCertificate: 'Apple Distribution: My Company (ABCDE12345)'
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

    String inputIpa = config.inputIpa
    String outputDir = config.outputDir
    String provisioningProfile = config.provisioningProfile
    String signingCertificate = config.signingCertificate
    String toolPath = config.toolPath ?: '/Applications/IntuneMAMPackager/Contents/MacOS/IntuneMAMPackager'
    boolean verbose = config.verbose as boolean

    assertOnMac()
    assertFileExists(inputIpa, 'Input IPA')
    assertFileExists(provisioningProfile, 'Provisioning profile')
    assertToolExists(toolPath)

    sh(script: "mkdir -p '${escapeSingleQuotes(outputDir)}'", label: 'Ensure output directory exists')

    String inputFileName = inputIpa.tokenize('/').last()
    String defaultOutputName = inputFileName.replaceAll(/(?i)\.ipa$/, '') + '-wrapped.ipa'
    String outputFileName = config.outputFileName ?: defaultOutputName
    String outputIpa = joinPath(outputDir, outputFileName)

    String command = buildCommand(toolPath, inputIpa, outputIpa, provisioningProfile, signingCertificate, verbose)

    echo "[intuneWrapIOS] Wrapping ${inputFileName} -> ${outputIpa}"
    int status = sh(script: command, returnStatus: true, label: 'Run IntuneMAMPackager')
    if (status != 0) {
        error "IntuneMAMPackager failed with exit code ${status}. Check the console log above for the tool's error output."
    }

    int existsStatus = sh(script: "test -f \"${outputIpa}\"", returnStatus: true)
    if (existsStatus != 0) {
        error "Wrapping reported success but the expected output file was not found: ${outputIpa}"
    }

    echo "[intuneWrapIOS] Successfully wrapped IPA: ${outputIpa}"
    return outputIpa
}

def buildCommand(String toolPath, String inputIpa, String outputIpa, String provisioningProfile,
                  String signingCertificate, boolean verbose) {
    List<String> cmd = []
    cmd << "\"${toolPath}\""
    cmd << '-i' << "\"${inputIpa}\""
    cmd << '-o' << "\"${outputIpa}\""
    cmd << '-p' << "\"${provisioningProfile}\""
    cmd << '-c' << "\"${signingCertificate}\""
    if (verbose) {
        cmd << '-v'
    }
    return cmd.join(' ')
}

def validateConfig(Map config) {
    List<String> required = ['inputIpa', 'outputDir', 'provisioningProfile', 'signingCertificate']
    List<String> missing = required.findAll { !config[it] }
    if (missing) {
        error "intuneWrapIOS is missing required parameter(s): ${missing.join(', ')}"
    }
    if (!(config.inputIpa ==~ /(?i).+\.ipa$/)) {
        error "inputIpa must point to a .ipa file, got: ${config.inputIpa}"
    }
}

def assertOnMac() {
    String uname = sh(script: 'uname -s', returnStdout: true).trim()
    if (uname != 'Darwin') {
        error "intuneWrapIOS must run on a macOS agent because IntuneMAMPackager is a macOS-only binary. Detected OS: ${uname}"
    }
}

def assertFileExists(String path, String label) {
    int status = sh(script: "test -e \"${path}\"", returnStatus: true)
    if (status != 0) {
        error "${label} not found at: ${path}"
    }
}

def assertToolExists(String toolPath) {
    int status = sh(script: "test -x \"${toolPath}\"", returnStatus: true)
    if (status != 0) {
        error "IntuneMAMPackager binary not found or not executable at: ${toolPath}. Override the location with the 'toolPath' parameter if it is installed elsewhere."
    }
}

def joinPath(String dir, String file) {
    String normalizedDir = dir.endsWith('/') ? dir[0..-2] : dir
    return "${normalizedDir}/${file}"
}

def escapeSingleQuotes(String path) {
    return path.replace("'", "'\\''")
}
