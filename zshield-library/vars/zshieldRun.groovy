/**
 * zshieldRun
 * ----------
 * Runs one or more zShield CLI actions against a .nwproj file. Must run
 * where a macOS agent with zShield installed is available.
 *
 * Supported actions: 'upgrade', 'prepare', 'protect', 'analyze' (run in the order given).
 *
 * IMPORTANT — command syntax: zShield's exact CLI flags aren't publicly
 * documented, so this step assumes the common pattern:
 *
 *   <zshieldPath> <action> "<nwprojPath>" [actionArgs[action]] [extraArgs]
 *
 * If your zShield CLI needs different flags or argument order, pass them
 * via 'actionArgs' (per-action) and/or 'extraArgs' (applied to every call).
 * Nothing beyond "<action> <nwprojPath>" is inferred.
 *
 * Required parameters:
 *   nwprojPath   Path to the .nwproj file to operate on
 *   actions      List of one or more of: 'upgrade', 'prepare', 'protect', 'analyze'
 *
 * Optional parameters:
 *   actionArgs        Map<String, List<String>> of extra CLI args per action,
 *                      e.g. [protect: ['--output', '/path/to/out']]
 *   extraArgs         List<String> of extra CLI args appended to every action call
 *   zshieldPath       Path to the zshield executable (default: 'zshield' on PATH)
 *   workingDirectory  Directory to invoke the CLI from
 *                      (default: the .nwproj file's parent directory)
 *   failFast          Stop at the first failing action (default: true).
 *                      If false, all requested actions still run, then a single
 *                      error summarizing every failure is raised at the end.
 *   agentLabel        If set, this step allocates its own `node(agentLabel) { }` block.
 *                      Leave unset if the enclosing stage already runs on a macOS agent.
 *
 * Returns: Map<String, Integer> of action name -> exit code, for every action that ran.
 *
 * Example — prepare then protect, in one call:
 *   stage('zShield') {
 *       agent { label 'macos' }
 *       steps {
 *           script {
 *               zshieldRun(
 *                   nwprojPath: "${WORKSPACE}/App.nwproj",
 *                   actions: ['prepare', 'protect'],
 *                   actionArgs: [
 *                       protect: ['--output', "${WORKSPACE}/protected"]
 *                   ]
 *               )
 *           }
 *       }
 *   }
 *
 * Example — upgrade an older project file before working with it:
 *   zshieldRun(
 *       agentLabel: 'macos',
 *       nwprojPath: '/Users/ci/projects/App.nwproj',
 *       actions: ['upgrade']
 *   )
 */
def call(Map config = [:]) {
    if (config.agentLabel) {
        node(config.agentLabel) {
            return runActions(config)
        }
    }
    return runActions(config)
}

def runActions(Map config) {
    validateConfig(config)

    String nwprojPath = config.nwprojPath
    List<String> actions = config.actions as List<String>
    boolean failFast = config.containsKey('failFast') ? config.failFast as boolean : true
    Map actionArgs = (config.actionArgs ?: [:]) as Map
    List<String> globalExtraArgs = (config.extraArgs ?: []) as List
    String zshieldPath = config.zshieldPath ?: 'zshield'
    String workingDirectory = config.workingDirectory ?: parentDirOf(nwprojPath)

    assertOnMac()
    assertToolAvailable(zshieldPath)
    assertPathExists(nwprojPath, 'nwproj file')

    Map<String, Integer> results = [:]
    List<String> failures = []

    dir(workingDirectory) {
        for (String action in actions) {
            List<String> cmd = [zshieldPath, action, "\"${nwprojPath}\""]
            cmd.addAll(actionArgs[action] ?: [])
            cmd.addAll(globalExtraArgs)
            String command = cmd.join(' ')

            echo "[zshieldRun] Running '${action}' on ${nwprojPath}"
            int status = sh(script: command, returnStatus: true, label: "zshield ${action}")
            results[action] = status

            if (status != 0) {
                String msg = "zshield '${action}' failed with exit code ${status} for project: ${nwprojPath}"
                if (failFast) {
                    error msg
                }
                failures << msg
                echo "[zshieldRun] WARNING: ${msg} (continuing, failFast=false)"
            } else {
                echo "[zshieldRun] '${action}' completed successfully"
            }
        }
    }

    if (failures) {
        error "One or more zshield actions failed:\n" + failures.join('\n')
    }

    return results
}

def validateConfig(Map config) {
    if (!config.nwprojPath) {
        error "zshieldRun is missing required parameter: nwprojPath"
    }
    if (!(config.nwprojPath ==~ /(?i).+\.nwproj$/)) {
        error "nwprojPath must point to a .nwproj file, got: ${config.nwprojPath}"
    }
    if (!(config.actions instanceof List) || (config.actions as List).isEmpty()) {
        error "zshieldRun requires a non-empty 'actions' list, e.g. ['prepare', 'protect']"
    }
    List<String> actions = config.actions as List<String>
    List<String> allowedActions = ['upgrade', 'prepare', 'protect', 'analyze']
    List<String> invalid = actions.findAll { !(it in allowedActions) }
    if (invalid) {
        error "Unsupported zshield action(s): ${invalid.join(', ')}. Allowed actions: ${allowedActions.join(', ')}"
    }
}

def assertOnMac() {
    String uname = sh(script: 'uname -s', returnStdout: true).trim()
    if (uname != 'Darwin') {
        error "zshieldRun must run on a macOS agent with zShield installed. Detected OS: ${uname}"
    }
}

def assertToolAvailable(String zshieldPath) {
    int status = sh(
        script: "command -v '${zshieldPath}' >/dev/null 2>&1 || test -x '${zshieldPath}'",
        returnStatus: true
    )
    if (status != 0) {
        error "zShield CLI not found or not executable: '${zshieldPath}'. Override with the 'zshieldPath' parameter if it's installed elsewhere, or ensure it's on PATH."
    }
}

def assertPathExists(String path, String label) {
    int status = sh(script: "test -e \"${path}\"", returnStatus: true)
    if (status != 0) {
        error "${label} not found at: ${path}"
    }
}

def parentDirOf(String path) {
    int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return idx > 0 ? path.substring(0, idx) : '.'
}
