/**
 * zshieldPrepare
 * --------------
 * Convenience wrapper around zshieldRun for the single 'prepare' action.
 * Must run where a macOS agent with zShield installed is available.
 *
 * Required parameters:
 *   nwprojPath   Path to the .nwproj file to prepare
 *
 * Optional parameters: same as zshieldRun (actionArgs keyed by 'prepare',
 * extraArgs, zshieldPath, workingDirectory, agentLabel). failFast is not
 * meaningful for a single action and is ignored.
 *
 * Returns: the exit code (Integer) of the prepare command.
 *
 * Example:
 *   zshieldPrepare(
 *       agentLabel: 'macos',
 *       nwprojPath: '/Users/ci/projects/App.nwproj'
 *   )
 */
def call(Map config = [:]) {
    Map results = zshieldRun(config + [actions: ['prepare']])
    return results['prepare']
}
