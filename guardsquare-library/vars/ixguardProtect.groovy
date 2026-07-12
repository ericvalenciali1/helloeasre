/**
 * ixguardProtect
 * --------------
 * Protects an .ipa using Guardsquare's iXGuard. Must run where a macOS
 * agent with iXGuard installed is available.
 *
 * Underlying command (confirmed via Guardsquare's own CI documentation,
 * e.g. https://www.guardsquare.com/blog/how-to-set-up-ixguard-with-xcode-cloud):
 *
 *   ixguard -c=<configFile> -d=<workDir> -f -o=<outputIpa> <inputIpa>
 *
 * Required parameters:
 *   inputIpa      Path to the source .ipa to protect
 *   outputIpa     Path for the protected .ipa (may equal inputIpa to protect in place)
 *   configFile    Path to the ixguard.yml configuration file
 *
 * Optional parameters:
 *   workDir       Directory where iXGuard writes mapping.yml / protectionreport.html
 *                 (default: outputIpa's parent directory)
 *   force         Pass -f to overwrite an existing output file (default: true)
 *   ixguardPath   Path to the ixguard executable (default: 'ixguard' on PATH)
 *   extraArgs     List<String> of additional CLI args appended to the command
 *   agentLabel    If set, this step allocates its own `node(agentLabel) { }` block.
 *                 Leave unset if the enclosing stage already runs on a macOS agent.
 *
 * Returns: the absolute path (String) of the protected .ipa.
 *
 * Example:
 *   stage('Protect iOS') {
 *       agent { label 'macos' }
 *       steps {
 *           script {
 *               def protectedIpa = ixguardProtect(
 *                   inputIpa: "${WORKSPACE}/build/MyApp.ipa",
 *                   outputIpa: "${WORKSPACE}/protected/MyApp-protected.ipa",
 *                   configFile: "${WORKSPACE}/ixguard.yml"
 *               )
 *               archiveArtifacts artifacts: 'protected/*.ipa'
 *           }
 *       }
 *   }
 */
def call(Map config = [:]) {
    if (config.agentLabel) {
        node(config.agentLabel) {
            return runProtect(config)
        }
    }
    return runProtect(config)
}

def runProtect(Map config) {
    validateConfig(config)

    String inputIpa = config.inputIpa
    String outputIpa = config.outputIpa
    String configFile = config.configFile
    String ixguardPath = config.ixguardPath ?: 'ixguard'
    String workDir = config.workDir ?: parentDirOf(outputIpa)
    boolean force = config.containsKey('force') ? config.force as boolean : true
    List<String> extraArgs = (config.extraArgs ?: []) as List

    assertOnMac()
    assertToolAvailable(ixguardPath)
    assertPathExists(inputIpa, 'Input IPA')
    assertPathExists(configFile, 'iXGuard config file')

    sh(script: "mkdir -p \"${workDir}\"", label: 'Ensure iXGuard work directory exists')
    sh(script: "mkdir -p \"${parentDirOf(outputIpa)}\"", label: 'Ensure output directory exists')

    List<String> cmd = [ixguardPath]
    cmd << "-c=\"${configFile}\""
    cmd << "-d=\"${workDir}\""
    if (force) {
        cmd << '-f'
    }
    cmd << "-o=\"${outputIpa}\""
    cmd << "\"${inputIpa}\""
    cmd.addAll(extraArgs)

    String command = cmd.join(' ')
    echo "[ixguardProtect] Protecting ${inputIpa} -> ${outputIpa}"
    int status = sh(script: command, returnStatus: true, label: 'Run ixguard')
    if (status != 0) {
        error "ixguard failed with exit code ${status}. Check the console log above for the tool's error output."
    }

    int existsStatus = sh(script: "test -f \"${outputIpa}\"", returnStatus: true)
    if (existsStatus != 0) {
        error "iXGuard reported success but the expected output file was not found: ${outputIpa}"
    }

    echo "[ixguardProtect] Successfully protected IPA: ${outputIpa}"
    return outputIpa
}

def validateConfig(Map config) {
    List<String> required = ['inputIpa', 'outputIpa', 'configFile']
    List<String> missing = required.findAll { !config[it] }
    if (missing) {
        error "ixguardProtect is missing required parameter(s): ${missing.join(', ')}"
    }
    if (!(config.inputIpa ==~ /(?i).+\.ipa$/)) {
        error "inputIpa must point to a .ipa file, got: ${config.inputIpa}"
    }
    if (!(config.outputIpa ==~ /(?i).+\.ipa$/)) {
        error "outputIpa must point to a .ipa file, got: ${config.outputIpa}"
    }
}

def assertOnMac() {
    String uname = sh(script: 'uname -s', returnStdout: true).trim()
    if (uname != 'Darwin') {
        error "ixguardProtect must run on a macOS agent with iXGuard installed. Detected OS: ${uname}"
    }
}

def assertToolAvailable(String ixguardPath) {
    int status = sh(
        script: "command -v '${ixguardPath}' >/dev/null 2>&1 || test -x '${ixguardPath}'",
        returnStatus: true
    )
    if (status != 0) {
        error "ixguard executable not found or not executable: '${ixguardPath}'. Override with the 'ixguardPath' parameter if it's installed elsewhere, or ensure it's on PATH."
    }
}

def assertPathExists(String path, String label) {
    int status = sh(script: "test -e \"${path}\"", returnStatus: true)
    if (status != 0) {
        error "${label} not found at: ${path}"
    }
}

def parentDirOf(String path) {
    int idx = path.lastIndexOf('/')
    return idx > 0 ? path.substring(0, idx) : '.'
}
