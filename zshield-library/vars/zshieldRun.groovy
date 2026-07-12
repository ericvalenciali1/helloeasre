import org.example.zshield.ZShieldRunner

/**
 * zshieldRun
 * ----------
 * Runs one or more zShield CLI actions against a .nwproj file. Must run
 * where a macOS agent with zShield installed is available.
 *
 * Supported actions: 'upgrade', 'prepare', 'protect' (run in the order given).
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
 *   actions      List of one or more of: 'upgrade', 'prepare', 'protect'
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
 *                      exception summarizing every failure is thrown at the end.
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
    def runner = new ZShieldRunner(this, config.zshieldPath as String)
    return runner.run(config)
}
