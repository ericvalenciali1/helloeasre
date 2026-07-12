package org.example.intune

/**
 * Wraps an .ipa using the Microsoft Intune App Wrapping Tool for iOS
 * (IntuneMAMPackager). This binary only runs on macOS, so the pipeline
 * calling this class must already be executing on a macOS agent.
 *
 * Underlying CLI (per Microsoft docs):
 *   IntuneMAMPackager -i <input.ipa> -o <output.ipa> -p <mobileprovision> -c <cert SHA1 or name> [-v]
 */
class IntuneIOSWrapper implements Serializable {

    private static final String DEFAULT_TOOL_PATH =
        '/Applications/IntuneMAMPackager/Contents/MacOS/IntuneMAMPackager'

    private final def script
    private final String toolPath

    IntuneIOSWrapper(script, String toolPath = null) {
        this.script = script
        this.toolPath = toolPath ?: DEFAULT_TOOL_PATH
    }

    /**
     * @param config map with keys:
     *   inputIpa (required)            - path to source .ipa
     *   outputDir (required)           - directory to place the wrapped .ipa
     *   provisioningProfile (required) - path to .mobileprovision file
     *   signingCertificate (required)  - certificate SHA1 thumbprint or common name
     *   outputFileName (optional)      - defaults to "<input>-wrapped.ipa"
     *   verbose (optional boolean)     - pass -v to the tool
     * @return absolute path to the wrapped .ipa
     */
    String wrap(Map config) {
        validate(config)

        String inputIpa = config.inputIpa
        String outputDir = config.outputDir
        String provisioningProfile = config.provisioningProfile
        String signingCertificate = config.signingCertificate
        boolean verbose = config.verbose as boolean

        assertOnMac()
        assertFileExists(inputIpa, 'Input IPA')
        assertFileExists(provisioningProfile, 'Provisioning profile')
        assertToolExists()

        script.sh(script: "mkdir -p '${escapeSingleQuotes(outputDir)}'", label: 'Ensure output directory exists')

        String inputFileName = inputIpa.tokenize('/').last()
        String defaultOutputName = inputFileName.replaceAll(/(?i)\.ipa$/, '') + '-wrapped.ipa'
        String outputFileName = config.outputFileName ?: defaultOutputName
        String outputIpa = joinPath(outputDir, outputFileName)

        String command = buildCommand(inputIpa, outputIpa, provisioningProfile, signingCertificate, verbose)

        script.echo "[intuneWrapIOS] Wrapping ${inputFileName} -> ${outputIpa}"
        int status = script.sh(script: command, returnStatus: true, label: 'Run IntuneMAMPackager')
        if (status != 0) {
            throw new IntuneWrapperException("IntuneMAMPackager failed with exit code ${status}. Check the console log above for the tool's error output.")
        }

        int existsStatus = script.sh(script: "test -f \"${outputIpa}\"", returnStatus: true)
        if (existsStatus != 0) {
            throw new IntuneWrapperException("Wrapping reported success but the expected output file was not found: ${outputIpa}")
        }

        script.echo "[intuneWrapIOS] Successfully wrapped IPA: ${outputIpa}"
        return outputIpa
    }

    private String buildCommand(String inputIpa, String outputIpa, String provisioningProfile,
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

    private void validate(Map config) {
        List<String> required = ['inputIpa', 'outputDir', 'provisioningProfile', 'signingCertificate']
        List<String> missing = required.findAll { !config[it] }
        if (missing) {
            throw new IntuneWrapperException("intuneWrapIOS is missing required parameter(s): ${missing.join(', ')}")
        }
        if (!(config.inputIpa ==~ /(?i).+\.ipa$/)) {
            throw new IntuneWrapperException("inputIpa must point to a .ipa file, got: ${config.inputIpa}")
        }
    }

    private void assertOnMac() {
        String uname = script.sh(script: 'uname -s', returnStdout: true).trim()
        if (uname != 'Darwin') {
            throw new IntuneWrapperException(
                "intuneWrapIOS must run on a macOS agent because IntuneMAMPackager is a macOS-only binary. Detected OS: ${uname}"
            )
        }
    }

    private void assertFileExists(String path, String label) {
        int status = script.sh(script: "test -e \"${path}\"", returnStatus: true)
        if (status != 0) {
            throw new IntuneWrapperException("${label} not found at: ${path}")
        }
    }

    private void assertToolExists() {
        int status = script.sh(script: "test -x \"${toolPath}\"", returnStatus: true)
        if (status != 0) {
            throw new IntuneWrapperException(
                "IntuneMAMPackager binary not found or not executable at: ${toolPath}. " +
                "Override the location with the 'toolPath' parameter if it is installed elsewhere."
            )
        }
    }

    private String joinPath(String dir, String file) {
        String normalizedDir = dir.endsWith('/') ? dir[0..-2] : dir
        return "${normalizedDir}/${file}"
    }

    private String escapeSingleQuotes(String path) {
        return path.replace("'", "'\\''")
    }
}
