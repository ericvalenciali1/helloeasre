import org.example.guardsquare.IXGuardRunner

/**
 * ixguardProtect
 * --------------
 * Protects an .ipa using Guardsquare's iXGuard. Must run where a macOS
 * agent with iXGuard installed is available.
 *
 * Underlying command:
 *   ixguard -c=<configFile> -d=<workDir> -f -o=<outputIpa> <inputIpa>
 *
 * Required parameters:
 *   inputIpa      Path to the source .ipa to protect
 *   outputIpa     Path for the protected .ipa (may equal inputIpa to protect in place)
 *   configFile    Path to the ixguard.yml configuration file
 *
 * Optional parameters:
 *   workDir       Directory where iXGuard writes mapping.yml / protectionreport.html
 *                 (default: outputIpa's parent directory)
 *   force         Pass -f to overwrite an existing output file (default: true)
 *   ixguardPath   Path to the ixguard executable (default: 'ixguard' on PATH)
 *   extraArgs     List<String> of additional CLI args appended to the command
 *   agentLabel    If set, this step allocates its own `node(agentLabel) { }` block.
 *                 Leave unset if the enclosing stage already runs on a macOS agent.
 *
 * Returns: the absolute path (String) of the protected .ipa.
 *
 * Example:
 *   stage('Protect iOS') {
 *       agent { label 'macos' }
 *       steps {
 *           script {
 *               def protectedIpa = ixguardProtect(
 *                   inputIpa: "${WORKSPACE}/build/MyApp.ipa",
 *                   outputIpa: "${WORKSPACE}/protected/MyApp-protected.ipa",
 *                   configFile: "${WORKSPACE}/ixguard.yml"
 *               )
 *               archiveArtifacts artifacts: 'protected/*.ipa'
 *           }
 *       }
 *   }
 */
def call(Map config = [:]) {
    if (config.agentLabel) {
        node(config.agentLabel) {
            return runProtect(config)
        }
    }
    return runProtect(config)
}

def runProtect(Map config) {
    def runner = new IXGuardRunner(this, config.ixguardPath as String)
    return runner.run(config)
}
