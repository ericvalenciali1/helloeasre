import org.example.intune.IntuneIOSWrapper

/**
 * intuneWrapIOS
 * -------------
 * Wraps an .ipa using the Microsoft Intune App Wrapping Tool for iOS
 * (IntuneMAMPackager). Must run where a macOS agent is available, since
 * the tool is a macOS-only binary.
 *
 * Required parameters:
 *   inputIpa             Path to the source .ipa to wrap
 *   outputDir             Directory where the wrapped .ipa will be placed
 *   provisioningProfile   Path to the .mobileprovision file
 *   signingCertificate    Code signing certificate common name OR SHA1 thumbprint
 *
 * Optional parameters:
 *   toolPath        Path to the IntuneMAMPackager binary
 *                   (default: /Applications/IntuneMAMPackager/Contents/MacOS/IntuneMAMPackager)
 *   outputFileName  Name for the wrapped .ipa (default: "<input>-wrapped.ipa")
 *   verbose         Pass -v to the tool (default: false)
 *   agentLabel      If set, this step allocates its own `node(agentLabel) { }` block.
 *                   Leave unset if the enclosing stage already runs on a macOS agent
 *                   (e.g. `agent { label 'macos' }` in a declarative stage).
 *
 * Returns: the absolute path (String) of the wrapped .ipa.
 *
 * Example (scripted, step manages its own node):
 *   def wrappedIpa = intuneWrapIOS(
 *       agentLabel: 'macos',
 *       inputIpa: '/Users/ci/builds/MyApp.ipa',
 *       outputDir: '/Users/ci/builds/wrapped',
 *       provisioningProfile: '/Users/ci/profiles/MyApp.mobileprovision',
 *       signingCertificate: '1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B'
 *   )
 *
 * Example (declarative, stage already pins the agent):
 *   stage('Wrap iOS') {
 *       agent { label 'macos' }
 *       steps {
 *           script {
 *               intuneWrapIOS(
 *                   inputIpa: "${WORKSPACE}/build/MyApp.ipa",
 *                   outputDir: "${WORKSPACE}/wrapped",
 *                   provisioningProfile: "${WORKSPACE}/profiles/MyApp.mobileprovision",
 *                   signingCertificate: 'Apple Distribution: My Company (ABCDE12345)'
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
    def wrapper = new IntuneIOSWrapper(this, config.toolPath as String)
    return wrapper.wrap(config)
}
