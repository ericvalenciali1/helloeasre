package org.example.guardsquare

/**
 * Protects an .apk with Guardsquare's DexGuard, running standalone
 * (outside the Gradle plugin flow) against an already-built APK.
 *
 * ⚠️ SYNTAX NOT INDEPENDENTLY VERIFIED: DexGuard's standalone CLI isn't
 * publicly documented in the same detail as iXGuard's — it ships to paying
 * customers as part of the DexGuard download. This class assumes DexGuard
 * follows the same standalone invocation convention as its own foundation,
 * ProGuard (see https://www.guardsquare.com/manual/setup/standalone):
 *
 *   java -jar <dexguardJarPath> -injars <inputApk> -outjars <outputApk> @<configFile> [extraArgs]
 *
 * (or, if 'dexguardPath' is not a .jar, it's invoked directly as an
 * executable with the same arguments). If your actual DexGuard CLI differs,
 * adjust buildCommand() below, or pass the full override via 'extraArgs' /
 * 'commandOverride'.
 */
class DexGuardRunner implements Serializable {

    private final def script
    private final String dexguardPath

    DexGuardRunner(script, String dexguardPath) {
        this.script = script
        this.dexguardPath = dexguardPath
    }

    /**
     * @param config map with keys:
     *   inputApk (required)      - path to the source .apk to protect
     *   outputApk (required)     - path for the protected .apk
     *   configFile (required)    - path to the DexGuard config (e.g. dexguard-project.pro),
     *                              applied via '@configFile'
     *   dexguardPath (required, via constructor) - path to dexguard.jar, or to a
     *                              dexguard launcher executable
     *   javaPath (optional)      - java executable to use when dexguardPath is a .jar
     *                              (default: 'java' on PATH)
     *   workingDirectory (optional) - directory to invoke the CLI from
     *                              (default: outputApk's parent directory)
     *   extraArgs (optional)     - List<String> of additional CLI args appended to the command
     *   commandOverride (optional) - List<String> full command to run instead of the built one,
     *                              for when the assumed syntax above doesn't match your DexGuard CLI.
     *                              inputApk/outputApk/configFile are still validated to exist.
     * @return the absolute path (String) of the protected .apk
     */
    String run(Map config) {
        validate(config)

        String inputApk = config.inputApk
        String outputApk = config.outputApk
        String configFile = config.configFile
        String javaPath = config.javaPath ?: 'java'
        String workingDirectory = config.workingDirectory ?: parentDir(outputApk)
        List<String> extraArgs = (config.extraArgs ?: []) as List

        assertOnMac()
        assertPathExists(dexguardPath, 'DexGuard executable/jar')
        assertPathExists(inputApk, 'Input APK')
        assertPathExists(configFile, 'DexGuard config file')

        script.sh(script: "mkdir -p \"${workingDirectory}\"", label: 'Ensure working directory exists')
        String outputParentDir = parentDir(outputApk)
        script.sh(script: "mkdir -p \"${outputParentDir}\"", label: 'Ensure output directory exists')

        String command = config.commandOverride
            ? (config.commandOverride as List).join(' ')
            : buildCommand(inputApk, outputApk, configFile, javaPath, extraArgs)

        script.dir(workingDirectory) {
            script.echo "[dexguardProtect] Protecting ${inputApk} -> ${outputApk}"
            int status = script.sh(script: command, returnStatus: true, label: 'Run dexguard')
            if (status != 0) {
                throw new GuardsquareException("dexguard failed with exit code ${status}. Check the console log above for the tool's error output.")
            }
        }

        int existsStatus = script.sh(script: "test -f \"${outputApk}\"", returnStatus: true)
        if (existsStatus != 0) {
            throw new GuardsquareException("DexGuard reported success but the expected output file was not found: ${outputApk}")
        }

        script.echo "[dexguardProtect] Successfully protected APK: ${outputApk}"
        return outputApk
    }

    private String buildCommand(String inputApk, String outputApk, String configFile, String javaPath, List<String> extraArgs) {
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

    private void validate(Map config) {
        if (!dexguardPath) {
            throw new GuardsquareException("dexguardProtect is missing required parameter: dexguardPath")
        }
        List<String> required = ['inputApk', 'outputApk', 'configFile']
        List<String> missing = required.findAll { !config[it] }
        if (missing) {
            throw new GuardsquareException("dexguardProtect is missing required parameter(s): ${missing.join(', ')}")
        }
        if (!(config.inputApk ==~ /(?i).+\.apk$/)) {
            throw new GuardsquareException("inputApk must point to a .apk file, got: ${config.inputApk}")
        }
        if (!(config.outputApk ==~ /(?i).+\.apk$/)) {
            throw new GuardsquareException("outputApk must point to a .apk file, got: ${config.outputApk}")
        }
    }

    private void assertOnMac() {
        String uname = script.sh(script: 'uname -s', returnStdout: true).trim()
        if (uname != 'Darwin') {
            throw new GuardsquareException("dexguardProtect must run on a macOS agent with DexGuard installed. Detected OS: ${uname}")
        }
    }

    private void assertPathExists(String path, String label) {
        int status = script.sh(script: "test -e \"${path}\"", returnStatus: true)
        if (status != 0) {
            throw new GuardsquareException("${label} not found at: ${path}")
        }
    }

    private String parentDir(String path) {
        int idx = path.lastIndexOf('/')
        return idx > 0 ? path.substring(0, idx) : '.'
    }
}
