import org.example.guardsquare.DexGuardRunner

/**
 * dexguardProtect
 * ----------------
 * Protects an .apk using Guardsquare's DexGuard, run standalone against an
 * already-built APK. Must run where a macOS agent with DexGuard installed
 * is available.
 *
 * ⚠️ SYNTAX NOT INDEPENDENTLY VERIFIED: unlike iXGuard, DexGuard's
 * standalone CLI flags aren't publicly documented in detail (DexGuard is
 * normally driven by its Gradle plugin). This step assumes DexGuard follows
 * the same standalone convention as ProGuard, its open-source foundation:
 *
 *   java -jar <dexguardPath> -injars <inputApk> -outjars <outputApk> @<configFile> [extraArgs]
 *
 * (or, if 'dexguardPath' isn't a .jar, it's invoked directly with the same
 * arguments). If this doesn't match your actual DexGuard CLI, pass
 * 'commandOverride' with the exact command you use (inputApk/outputApk/
 * configFile are still validated to exist beforehand), or adjust
 * DexGuardRunner.buildCommand() in the library source directly.
 *
 * Required parameters:
 *   inputApk       Path to the source .apk to protect
 *   outputApk      Path for the protected .apk
 *   configFile     Path to the DexGuard config file (e.g. dexguard-project.pro),
 *                  applied via '@configFile'
 *   dexguardPath   Path to dexguard.jar or a dexguard launcher executable
 *
 * Optional parameters:
 *   javaPath          Java executable to use when dexguardPath is a .jar (default: 'java' on PATH)
 *   workingDirectory  Directory to invoke the CLI from (default: outputApk's parent directory)
 *   extraArgs         List<String> of additional CLI args appended to the built command
 *   commandOverride   List<String> full command to run instead of the built one
 *   agentLabel        If set, this step allocates its own `node(agentLabel) { }` block.
 *                      Leave unset if the enclosing stage already runs on a macOS agent.
 *
 * Returns: the absolute path (String) of the protected .apk.
 *
 * Example:
 *   stage('Protect Android') {
 *       agent { label 'macos' }
 *       steps {
 *           script {
 *               def protectedApk = dexguardProtect(
 *                   inputApk: "${WORKSPACE}/build/MyApp.apk",
 *                   outputApk: "${WORKSPACE}/protected/MyApp-protected.apk",
 *                   configFile: "${WORKSPACE}/dexguard-project.pro",
 *                   dexguardPath: '/Applications/DexGuard/lib/dexguard.jar'
 *               )
 *               archiveArtifacts artifacts: 'protected/*.apk'
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
    def runner = new DexGuardRunner(this, config.dexguardPath as String)
    return runner.run(config)
}
