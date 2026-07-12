/**
 * zshieldAnalyze
 * --------------
 * Convenience wrapper around zshieldRun for the single 'analyze' action.
 * Must run where a macOS agent with zShield installed is available.
 *
 * Required parameters:
 *   nwprojPath   Path to the .nwproj file to analyze
 *
 * Optional parameters: same as zshieldRun (actionArgs keyed by 'analyze',
 * extraArgs, zshieldPath, workingDirectory, agentLabel). failFast is not
 * meaningful for a single action and is ignored.
 *
 * Returns: the exit code (Integer) of the analyze command.
 *
 * Example:
 *   zshieldAnalyze(
 *       agentLabel: 'macos',
 *       nwprojPath: '/Users/ci/projects/App.nwproj'
 *   )
 */
def call(Map config = [:]) {
    Map results = zshieldRun(config + [actions: ['analyze']])
    return results['analyze']
}
