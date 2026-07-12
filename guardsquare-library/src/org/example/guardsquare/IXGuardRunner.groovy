package org.example.guardsquare

/**
 * Protects an .ipa with Guardsquare's iXGuard. iXGuard is a macOS
 * command-line tool, so the pipeline calling this class must already be
 * executing on a macOS agent.
 *
 * Underlying CLI (confirmed via Guardsquare's own CI documentation, e.g.
 * https://www.guardsquare.com/blog/how-to-set-up-ixguard-with-xcode-cloud):
 *
 *   ixguard -c=<config.yml> -d=<workDir> -f -o=<outputIpa> <inputIpa>
 *
 *   -c   path to the ixguard.yml configuration file
 *   -d   working directory where iXGuard writes mapping.yml and
 *        protectionreport.html
 *   -f   force overwrite of an existing output file
 *   -o   path of the protected output .ipa (may equal the input path,
 *        which protects the file in place)
 */
class IXGuardRunner implements Serializable {

    private static final String DEFAULT_IXGUARD_PATH = 'ixguard'

    private final def script
    private final String ixguardPath

    IXGuardRunner(script, String ixguardPath = null) {
        this.script = script
        this.ixguardPath = ixguardPath ?: DEFAULT_IXGUARD_PATH
    }

    /**
     * @param config map with keys:
     *   inputIpa (required)     - path to the source .ipa to protect
     *   outputIpa (required)    - path for the protected .ipa (may equal inputIpa)
     *   configFile (required)   - path to the ixguard.yml configuration file
     *   workDir (optional)      - directory for mapping.yml / protectionreport.html
     *                             (default: outputIpa's parent directory)
     *   force (optional boolean)- pass -f to overwrite an existing output (default: true)
     *   extraArgs (optional)    - List<String> of additional CLI args appended to the command
     * @return the absolute path (String) of the protected .ipa
     */
    String run(Map config) {
        validate(config)

        String inputIpa = config.inputIpa
        String outputIpa = config.outputIpa
        String configFile = config.configFile
        String workDir = config.workDir ?: parentDir(outputIpa)
        boolean force = config.containsKey('force') ? config.force as boolean : true
        List<String> extraArgs = (config.extraArgs ?: []) as List

        assertOnMac()
        assertToolAvailable()
        assertPathExists(inputIpa, 'Input IPA')
        assertPathExists(configFile, 'iXGuard config file')

        script.sh(script: "mkdir -p \"${workDir}\"", label: 'Ensure iXGuard work directory exists')
        String outputParentDir = parentDir(outputIpa)
        script.sh(script: "mkdir -p \"${outputParentDir}\"", label: 'Ensure output directory exists')

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
        script.echo "[ixguardProtect] Protecting ${inputIpa} -> ${outputIpa}"
        int status = script.sh(script: command, returnStatus: true, label: 'Run ixguard')
        if (status != 0) {
            throw new GuardsquareException("ixguard failed with exit code ${status}. Check the console log above for the tool's error output.")
        }

        int existsStatus = script.sh(script: "test -f \"${outputIpa}\"", returnStatus: true)
        if (existsStatus != 0) {
            throw new GuardsquareException("iXGuard reported success but the expected output file was not found: ${outputIpa}")
        }

        script.echo "[ixguardProtect] Successfully protected IPA: ${outputIpa}"
        return outputIpa
    }

    private void validate(Map config) {
        List<String> required = ['inputIpa', 'outputIpa', 'configFile']
        List<String> missing = required.findAll { !config[it] }
        if (missing) {
            throw new GuardsquareException("ixguardProtect is missing required parameter(s): ${missing.join(', ')}")
        }
        if (!(config.inputIpa ==~ /(?i).+\.ipa$/)) {
            throw new GuardsquareException("inputIpa must point to a .ipa file, got: ${config.inputIpa}")
        }
        if (!(config.outputIpa ==~ /(?i).+\.ipa$/)) {
            throw new GuardsquareException("outputIpa must point to a .ipa file, got: ${config.outputIpa}")
        }
    }

    private void assertOnMac() {
        String uname = script.sh(script: 'uname -s', returnStdout: true).trim()
        if (uname != 'Darwin') {
            throw new GuardsquareException("ixguardProtect must run on a macOS agent with iXGuard installed. Detected OS: ${uname}")
        }
    }

    private void assertToolAvailable() {
        int status = script.sh(
            script: "command -v '${ixguardPath}' >/dev/null 2>&1 || test -x '${ixguardPath}'",
            returnStatus: true
        )
        if (status != 0) {
            throw new GuardsquareException(
                "ixguard executable not found or not executable: '${ixguardPath}'. " +
                "Override with the 'ixguardPath' parameter if it's installed elsewhere, or ensure it's on PATH."
            )
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
