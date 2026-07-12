/**
 * dexguardProtect
 * ----------------
 * Protects an .apk using Guardsquare's DexGuard, run standalone against an
 * already-built APK. Must run where a macOS agent with DexGuard installed
 * is available.
 *
 * ⚠️ SYNTAX NOT INDEPENDENTLY VERIFIED: unlike iXGuard, DexGuard's
 * standalone CLI flags aren't publicly documented in detail (DexGuard is
 * normally driven by its Gradle plugin). This step assumes DexGuard follows
 * the same standalone convention as ProGuard, its open-source foundation:
 *
 *   java -jar <dexguardPath> -injars <inputApk> -outjars <outputApk> @<configFile> [extraArgs]
 *
 * (or, if 'dexguardPath' isn't a .jar, it's invoked directly with the same
 * arguments). If this doesn't match your actual DexGuard CLI, pass
 * 'commandOverride' with the exact command you use (inputApk/outputApk/
 * configFile are still validated to exist beforehand), or edit
 * buildDexguardCommand() below directly.
 *
 * Required parameters:
 *   inputApk       Path to the source .apk to protect
 *   outputApk      Path for the protected .apk
 *   configFile     Path to the DexGuard config file (e.g. dexguard-project.pro),
 *                  applied via '@configFile'
 *   dexguardPath   Path to dexguard.jar or a dexguard launcher executable
 *
 * Optional parameters:
 *   javaPath          Java executable to use when dexguardPath is a .jar (default: 'java' on PATH)
 *   workingDirectory  Directory to invoke the CLI from (default: outputApk's parent directory)
 *   extraArgs         List<String> of additional CLI args appended to the built command
 *   commandOverride   List<String> full command to run instead of the built one
 *   agentLabel        If set, this step allocates its own `node(agentLabel) { }` block.
 *                      Leave unset if the enclosing stage already runs on a macOS agent.
 *
 * Returns: the absolute path (String) of the protected .apk.
 *
 * Example:
 *   stage('Protect Android') {
 *       agent { label 'macos' }
 *       steps {
 *           script {
 *               def protectedApk = dexguardProtect(
 *                   inputApk: "${WORKSPACE}/build/MyApp.apk",
 *                   outputApk: "${WORKSPACE}/protected/MyApp-protected.apk",
 *                   configFile: "${WORKSPACE}/dexguard-project.pro",
 *                   dexguardPath: '/Applications/DexGuard/lib/dexguard.jar'
 *               )
 *               archiveArtifacts artifacts: 'protected/*.apk'
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

    String inputApk = config.inputApk
    String outputApk = config.outputApk
    String configFile = config.configFile
    String dexguardPath = config.dexguardPath
    String javaPath = config.javaPath ?: 'java'
    String workingDirectory = config.workingDirectory ?: parentDirOf(outputApk)
    List<String> extraArgs = (config.extraArgs ?: []) as List

    assertOnMac()
    assertPathExists(dexguardPath, 'DexGuard executable/jar')
    assertPathExists(inputApk, 'Input APK')
    assertPathExists(configFile, 'DexGuard config file')

    sh(script: "mkdir -p \"${workingDirectory}\"", label: 'Ensure working directory exists')
    sh(script: "mkdir -p \"${parentDirOf(outputApk)}\"", label: 'Ensure output directory exists')

    String command = config.commandOverride
        ? (config.commandOverride as List).join(' ')
        : buildDexguardCommand(dexguardPath, inputApk, outputApk, configFile, javaPath, extraArgs)

    dir(workingDirectory) {
        echo "[dexguardProtect] Protecting ${inputApk} -> ${outputApk}"
        int status = sh(script: command, returnStatus: true, label: 'Run dexguard')
        if (status != 0) {
            error "dexguard failed with exit code ${status}. Check the console log above for the tool's error output."
        }
    }

    int existsStatus = sh(script: "test -f \"${outputApk}\"", returnStatus: true)
    if (existsStatus != 0) {
        error "DexGuard reported success but the expected output file was not found: ${outputApk}"
    }

    echo "[dexguardProtect] Successfully protected APK: ${outputApk}"
    return outputApk
}

def buildDexguardCommand(String dexguardPath, String inputApk, String outputApk, String configFile,
                          String javaPath, List<String> extraArgs) {
    List<String> cmd = []
    if (dexguardPath ==~ /(?i).+\.jar$/) {
        cmd << javaPath << '-jar' << "\"${dexguardPath}\""
    } else {
        cmd << "\"${dexguardPath}\""
    }
    cmd << '-injars' << "\"${inputApk}\""
    cmd << '-outjars' << "\"${outputApk}\""
    cmd << "@\"${configFile}\""
    cmd.addAll(extraArgs)
    return cmd.join(' ')
}

def validateConfig(Map config) {
    if (!config.dexguardPath) {
        error "dexguardProtect is missing required parameter: dexguardPath"
    }
    List<String> required = ['inputApk', 'outputApk', 'configFile']
    List<String> missing = required.findAll { !config[it] }
    if (missing) {
        error "dexguardProtect is missing required parameter(s): ${missing.join(', ')}"
    }
    if (!(config.inputApk ==~ /(?i).+\.apk$/)) {
        error "inputApk must point to a .apk file, got: ${config.inputApk}"
    }
    if (!(config.outputApk ==~ /(?i).+\.apk$/)) {
        error "outputApk must point to a .apk file, got: ${config.outputApk}"
    }
}

def assertOnMac() {
    String uname = sh(script: 'uname -s', returnStdout: true).trim()
    if (uname != 'Darwin') {
        error "dexguardProtect must run on a macOS agent with DexGuard installed. Detected OS: ${uname}"
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
