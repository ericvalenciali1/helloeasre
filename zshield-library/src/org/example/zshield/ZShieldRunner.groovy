package org.example.zshield

/**
 * Runs zShield CLI actions against a .nwproj file. Must run on a macOS
 * agent with the zShield CLI installed.
 *
 * NOTE ON COMMAND SYNTAX: zShield's exact CLI flags are not publicly
 * documented, so this class assumes the common pattern:
 *
 *   <zshieldPath> <action> "<nwprojPath>" [action-specific args] [global extra args]
 *
 * e.g.  zshield prepare "/path/to/App.nwproj"
 *       zshield protect "/path/to/App.nwproj" --output "/path/to/out"
 *
 * If your actual zShield CLI expects a different argument order or flags
 * (e.g. the project path behind a --project flag), pass them via
 * 'actionArgs' / 'extraArgs' — everything after the action name is exactly
 * what you provide, nothing is inferred beyond the leading action + path.
 */
class ZShieldRunner implements Serializable {

    static final List<String> ALLOWED_ACTIONS = ['upgrade', 'prepare', 'protect']
    static final String DEFAULT_ZSHIELD_PATH = 'zshield'

    private final def script
    private final String zshieldPath

    ZShieldRunner(script, String zshieldPath = null) {
        this.script = script
        this.zshieldPath = zshieldPath ?: DEFAULT_ZSHIELD_PATH
    }

    /**
     * @param config map with keys:
     *   nwprojPath (required)       - path to the .nwproj file to operate on
     *   actions (required)          - list of one or more of: 'upgrade', 'prepare', 'protect',
     *                                  run in the given order
     *   actionArgs (optional)       - Map<String, List<String>> of extra CLI args per action,
     *                                  e.g. [protect: ['--output', '/path/to/out']]
     *   extraArgs (optional)        - List<String> of extra CLI args appended to every action call
     *   zshieldPath (optional)      - path to the zshield executable (default: 'zshield' on PATH)
     *   workingDirectory (optional) - directory to invoke the CLI from
     *                                  (default: the .nwproj file's parent directory)
     *   failFast (optional boolean) - stop at the first failing action (default: true).
     *                                  If false, all requested actions run regardless of
     *                                  earlier failures, then an exception summarizing every
     *                                  failure is thrown at the end.
     * @return Map<String, Integer> of action name -> exit code, for every action that ran
     */
    Map<String, Integer> run(Map config) {
        validate(config)

        String nwprojPath = config.nwprojPath
        List<String> actions = config.actions as List<String>
        boolean failFast = config.containsKey('failFast') ? config.failFast as boolean : true
        Map actionArgs = (config.actionArgs ?: [:]) as Map
        List<String> globalExtraArgs = (config.extraArgs ?: []) as List
        String workingDirectory = config.workingDirectory ?: parentDir(nwprojPath)

        assertOnMac()
        assertToolAvailable()
        assertPathExists(nwprojPath, 'nwproj file')

        Map<String, Integer> results = [:]
        List<String> failures = []

        script.dir(workingDirectory) {
            for (String action in actions) {
                List<String> cmd = [zshieldPath, action, "\"${nwprojPath}\""]
                cmd.addAll(actionArgs[action] ?: [])
                cmd.addAll(globalExtraArgs)
                String command = cmd.join(' ')

                script.echo "[zshieldRun] Running '${action}' on ${nwprojPath}"
                int status = script.sh(script: command, returnStatus: true, label: "zshield ${action}")
                results[action] = status

                if (status != 0) {
                    String msg = "zshield '${action}' failed with exit code ${status} for project: ${nwprojPath}"
                    if (failFast) {
                        throw new ZShieldException(msg)
                    }
                    failures << msg
                    script.echo "[zshieldRun] WARNING: ${msg} (continuing, failFast=false)"
                } else {
                    script.echo "[zshieldRun] '${action}' completed successfully"
                }
            }
        }

        if (failures) {
            throw new ZShieldException("One or more zshield actions failed:\n" + failures.join('\n'))
        }

        return results
    }

    private void validate(Map config) {
        if (!config.nwprojPath) {
            throw new ZShieldException("zshieldRun is missing required parameter: nwprojPath")
        }
        if (!(config.nwprojPath ==~ /(?i).+\.nwproj$/)) {
            throw new ZShieldException("nwprojPath must point to a .nwproj file, got: ${config.nwprojPath}")
        }
        if (!(config.actions instanceof List) || (config.actions as List).isEmpty()) {
            throw new ZShieldException("zshieldRun requires a non-empty 'actions' list, e.g. ['prepare', 'protect']")
        }
        List<String> actions = config.actions as List<String>
        List<String> invalid = actions.findAll { !(it in ALLOWED_ACTIONS) }
        if (invalid) {
            throw new ZShieldException(
                "Unsupported zshield action(s): ${invalid.join(', ')}. Allowed actions: ${ALLOWED_ACTIONS.join(', ')}"
            )
        }
    }

    private void assertOnMac() {
        String uname = script.sh(script: 'uname -s', returnStdout: true).trim()
        if (uname != 'Darwin') {
            throw new ZShieldException("zshieldRun must run on a macOS agent with zShield installed. Detected OS: ${uname}")
        }
    }

    private void assertToolAvailable() {
        int status = script.sh(
            script: "command -v '${zshieldPath}' >/dev/null 2>&1 || test -x '${zshieldPath}'",
            returnStatus: true
        )
        if (status != 0) {
            throw new ZShieldException(
                "zShield CLI not found or not executable: '${zshieldPath}'. " +
                "Override with the 'zshieldPath' parameter if it's installed elsewhere, or ensure it's on PATH."
            )
        }
    }

    private void assertPathExists(String path, String label) {
        int status = script.sh(script: "test -e \"${path}\"", returnStatus: true)
        if (status != 0) {
            throw new ZShieldException("${label} not found at: ${path}")
        }
    }

    private String parentDir(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return idx > 0 ? path.substring(0, idx) : '.'
    }
}
