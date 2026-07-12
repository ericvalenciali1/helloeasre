/**
 * codeProtect
 * -----------
 * Dispatcher that selects and runs one of three app-protection tools:
 *   - 'intune'      → Microsoft Intune App Wrapping Tool
 *                      (intuneWrapIOS / intuneWrapAndroid, from intune-wrap-library)
 *   - 'zshield'      → zShield (zshieldRun, from zshield-library)
 *   - 'guardsquare'  → iXGuard / DexGuard
 *                      (ixguardProtect / dexguardProtect, from guardsquare-library)
 *
 * This step does not implement any protection logic itself — it only
 * routes to the matching global step and forwards your config to it
 * unchanged. All three of intune-wrap-library, zshield-library, and
 * guardsquare-library must be loaded in the same Jenkinsfile (via
 * @Library) for the target step to be available.
 *
 * Required parameters:
 *   tool       One of: 'intune', 'zshield', 'guardsquare'
 *   platform   One of: 'ios', 'android'. Required for 'intune' and
 *              'guardsquare' (they have a separate step per platform).
 *              Not used for 'zshield', which operates on a .nwproj
 *              regardless of platform — omit it or pass either value,
 *              it's ignored.
 *
 * All other keys in the config map are passed straight through to the
 * underlying step, so use whatever parameters *that* step expects:
 *
 *   tool: 'intune', platform: 'ios'       → see intuneWrapIOS (intuneWrapIOS.groovy)
 *     inputIpa, outputDir, provisioningProfile, signingCertificate, ...
 *
 *   tool: 'intune', platform: 'android'   → see intuneWrapAndroid (intuneWrapAndroid.groovy)
 *     inputApk, outputDir, bundletoolPath (if .aab), keyStorePath, ...
 *
 *   tool: 'zshield'                        → see zshieldRun (zshieldRun.groovy)
 *     nwprojPath, actions, actionArgs, extraArgs, ...
 *
 *   tool: 'guardsquare', platform: 'ios'   → see ixguardProtect (ixguardProtect.groovy)
 *     inputIpa, outputIpa, configFile, ...
 *
 *   tool: 'guardsquare', platform: 'android' → see dexguardProtect (dexguardProtect.groovy)
 *     inputApk, outputApk, configFile, dexguardPath, ...
 *
 * Returns: whatever the underlying step returns (a path String, or for
 * zshield a Map<String, Integer> of action -> exit code).
 *
 * Example:
 *   codeProtect(
 *       tool: 'guardsquare',
 *       platform: 'ios',
 *       inputIpa: "${WORKSPACE}/build/MyApp.ipa",
 *       outputIpa: "${WORKSPACE}/protected/MyApp-protected.ipa",
 *       configFile: "${WORKSPACE}/ixguard.yml"
 *   )
 */
def call(Map config = [:]) {
    String tool = config.tool
    String platform = config.platform

    List<String> allowedTools = ['intune', 'zshield', 'guardsquare']
    if (!tool || !(tool in allowedTools)) {
        error "codeProtect requires 'tool' to be one of: ${allowedTools.join(', ')}. Got: ${tool}"
    }

    Map toolConfig = config.findAll { key, value -> !(key in ['tool', 'platform']) }

    switch (tool) {
        case 'intune':
            assertPlatform(platform)
            if (platform == 'ios') {
                return intuneWrapIOS(toolConfig)
            }
            return intuneWrapAndroid(toolConfig)
        case 'guardsquare':
            assertPlatform(platform)
            if (platform == 'ios') {
                return ixguardProtect(toolConfig)
            }
            return dexguardProtect(toolConfig)
        case 'zshield':
            return zshieldRun(toolConfig)
        default:
            // unreachable — tool was already validated above
            error "codeProtect: unhandled tool '${tool}'"
    }
}

def assertPlatform(String platform) {
    List<String> allowedPlatforms = ['ios', 'android']
    if (!platform || !(platform in allowedPlatforms)) {
        error "codeProtect requires 'platform' to be one of: ${allowedPlatforms.join(', ')} when 'tool' is 'intune' or 'guardsquare'. Got: ${platform}"
    }
}
