/**
 * zshieldProtect
 * --------------
 * Convenience wrapper around zshieldRun for the single 'protect' action.
 * Must run where a macOS agent with zShield installed is available.
 *
 * Required parameters:
 *   nwprojPath   Path to the .nwproj file to protect
 *
 * Optional parameters: same as zshieldRun (actionArgs keyed by 'protect',
 * extraArgs, zshieldPath, workingDirectory, agentLabel). failFast is not
 * meaningful for a single action and is ignored.
 *
 * Returns: the exit code (Integer) of the protect command.
 *
 * Example:
 *   zshieldProtect(
 *       agentLabel: 'macos',
 *       nwprojPath: '/Users/ci/projects/App.nwproj',
 *       extraArgs: ['--output', '/Users/ci/projects/protected']
 *   )
 */
def call(Map config = [:]) {
    Map results = zshieldRun(config + [actions: ['protect']])
    return results['protect']
}
