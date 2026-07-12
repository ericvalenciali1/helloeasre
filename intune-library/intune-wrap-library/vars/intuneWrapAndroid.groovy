import org.example.intune.IntuneAndroidWrapper

/**
 * intuneWrapAndroid
 * ------------------
 * Wraps an Android app using the Microsoft Intune App Wrapping Tool for
 * Android (Invoke-AppWrappingTool). Must run where a Windows agent is
 * available, since the tool is a Windows-only PowerShell module.
 *
 * Accepts either a .apk or a .aab as inputApk:
 *   - .apk is wrapped directly.
 *   - .aab is first converted to a universal .apk with Google's bundletool
 *     (the Intune wrapping tool only accepts .apk input), then that is
 *     wrapped. This requires 'bundletoolPath' and a JRE on the agent (the
 *     Intune tool itself already requires Java, so this is normally
 *     already present).
 *
 * Required parameters:
 *   inputApk    Path to the source .apk or .aab to wrap
 *   outputDir   Directory where the wrapped .apk will be placed
 *
 * Required only when inputApk is a .aab:
 *   bundletoolPath   Path to bundletool's .jar, used to build a universal
 *                    .apk from the app bundle before wrapping
 *
 * Optional parameters for signing the interim universal .apk generated
 * from an .aab (only relevant for .aab input — ignored for .apk input).
 * If omitted, bundletool falls back to its own default debug keystore, or
 * produces an unsigned interim APK if none exists on the agent. Since the
 * Intune wrapping tool discards any existing signing on its input anyway,
 * these normally don't need to match your release signing identity:
 *   bundleKeyStorePath                     Keystore to sign the interim APK with
 *                                           (defaults to keyStorePath below if unset)
 *   bundleKeyAlias                         Defaults to keyAlias below
 *   bundleKeyStorePasswordCredentialsId    Defaults to keyStorePasswordCredentialsId below
 *   bundleKeyPasswordCredentialsId         Defaults to keyPasswordCredentialsId below
 *
 * Optional parameters (only needed to re-sign the final wrapped output —
 * omit all of them if you don't want the wrapper to re-sign it):
 *   keyStorePath                    Path to the Java keystore (.jks/.keystore)
 *   keyAlias                        Alias of the signing key (required if keyStorePath set)
 *   keyStorePasswordCredentialsId   Jenkins "Secret text" credential ID holding
 *                                   the keystore password (required if keyStorePath set)
 *   keyPasswordCredentialsId        Jenkins "Secret text" credential ID holding the
 *                                   key password (defaults to keyStorePasswordCredentialsId)
 *   sigAlg                          Signature algorithm (default: SHA256withRSA)
 *
 * Other optional parameters:
 *   toolInstallDir   Install directory of the App Wrapping Tool
 *                    (default: C:\Program Files (x86)\Microsoft Intune Mobile
 *                     Application Management\Android\App Wrapping Tool)
 *   outputFileName   Name for the wrapped .apk (default: "<input>-wrapped.apk")
 *   verbose          Pass -Verbose to the tool (default: false)
 *   agentLabel       If set, this step allocates its own `node(agentLabel) { }` block.
 *                    Leave unset if the enclosing stage already runs on a Windows agent
 *                    (e.g. `agent { label 'windows' }` in a declarative stage).
 *
 * Returns: the absolute path (String) of the wrapped .apk.
 *
 * Example — wrapping an already-signed .apk (scripted, step manages its own node):
 *   def wrappedApk = intuneWrapAndroid(
 *       agentLabel: 'windows',
 *       inputApk: 'C:\\builds\\MyApp.apk',
 *       outputDir: 'C:\\builds\\wrapped',
 *       keyStorePath: 'C:\\keys\\myapp.keystore',
 *       keyAlias: 'myapp',
 *       keyStorePasswordCredentialsId: 'android-keystore-password',
 *       keyPasswordCredentialsId: 'android-key-password'
 *   )
 *
 * Example — wrapping a .aab (declarative, stage already pins the agent):
 *   stage('Wrap Android') {
 *       agent { label 'windows' }
 *       steps {
 *           script {
 *               intuneWrapAndroid(
 *                   inputApk: "${WORKSPACE}\\build\\MyApp.aab",
 *                   outputDir: "${WORKSPACE}\\wrapped",
 *                   bundletoolPath: 'C:\\tools\\bundletool-all.jar',
 *                   keyStorePath: 'C:\\keys\\myapp.keystore',
 *                   keyAlias: 'myapp',
 *                   keyStorePasswordCredentialsId: 'android-keystore-password'
 *               )
 *           }
 *       }
 *   }
 */
def call(Map config = [:]) {
    if (config.agentLabel) {
        node(config.agentLabel) {
            return runWrap(config)
        }
    }
    return runWrap(config)
}

def runWrap(Map config) {
    def wrapper = new IntuneAndroidWrapper(this, config.toolInstallDir as String)
    return wrapper.wrap(config)
}
