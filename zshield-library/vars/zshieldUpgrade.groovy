/**
 * zshieldUpgrade
 * --------------
 * Convenience wrapper around zshieldRun for the single 'upgrade' action —
 * upgrades a .nwproj file to the version expected by the installed zShield
 * CLI. Must run where a macOS agent with zShield installed is available.
 *
 * Required parameters:
 *   nwprojPath   Path to the .nwproj file to upgrade
 *
 * Optional parameters: same as zshieldRun (actionArgs keyed by 'upgrade',
 * extraArgs, zshieldPath, workingDirectory, agentLabel). failFast is not
 * meaningful for a single action and is ignored.
 *
 * Returns: the exit code (Integer) of the upgrade command.
 *
 * Example:
 *   zshieldUpgrade(
 *       agentLabel: 'macos',
 *       nwprojPath: '/Users/ci/projects/App.nwproj'
 *   )
 */
def call(Map config = [:]) {
    Map results = zshieldRun(config + [actions: ['upgrade']])
    return results['upgrade']
}
