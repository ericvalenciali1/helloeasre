# intune-wrap-library

Jenkins Shared Library providing two global pipeline steps that wrap mobile
app binaries with the **Microsoft Intune App Wrapping Tool**:

| Step               | Wraps           | Underlying tool                              | Required OS |
|---------------------|-----------------|-----------------------------------------------|-------------|
| `intuneWrapIOS`     | `.ipa`          | `IntuneMAMPackager`                            | macOS       |
| `intuneWrapAndroid` | `.apk` or `.aab`| `Invoke-AppWrappingTool` (PowerShell module)   | Windows     |

`.aab` (Android App Bundle) input is converted to a universal `.apk` with
[bundletool](https://github.com/google/bundletool) first, since the Intune
Android wrapping tool only accepts `.apk` — see the `intuneWrapAndroid`
section below.

Both tools are Microsoft binaries that must already be installed on the
respective agents — this library only orchestrates calling them from a
pipeline and does not ship the tools themselves.

## Repository layout (standard Jenkins Shared Library structure)

```
intune-wrap-library/
├── vars/
│   ├── intuneWrapIOS.groovy       # global step: intuneWrapIOS(...)
│   └── intuneWrapAndroid.groovy   # global step: intuneWrapAndroid(...)
├── src/org/example/intune/
│   ├── IntuneIOSWrapper.groovy      # command construction + validation for iOS
│   ├── IntuneAndroidWrapper.groovy  # command construction + validation for Android
│   └── IntuneWrapperException.groovy
├── test/
│   └── Jenkinsfile.example         # sample pipeline
└── README.md
```

Register this repo in Jenkins under **Manage Jenkins → System → Global
Pipeline Libraries** (or load per-Jenkinsfile with `@Library`).

## `intuneWrapIOS`

Calls `IntuneMAMPackager -i <input> -o <output> -p <profile> -c <cert> [-v]`.

| Parameter              | Required | Description                                                                 |
|-------------------------|:--------:|-------------------------------------------------------------------------------|
| `inputIpa`               | ✅       | Path to the source `.ipa` to wrap                                            |
| `outputDir`              | ✅       | Directory the wrapped `.ipa` will be written to                              |
| `provisioningProfile`    | ✅       | Path to the `.mobileprovision` file                                          |
| `signingCertificate`     | ✅       | Certificate **SHA1 thumbprint or common name** (must be in the agent's keychain) |
| `toolPath`               |          | Path to `IntuneMAMPackager` (default: `/Applications/IntuneMAMPackager/Contents/MacOS/IntuneMAMPackager`) |
| `outputFileName`         |          | Wrapped file name (default: `<input>-wrapped.ipa`)                           |
| `verbose`                |          | Passes `-v` (default: `false`)                                               |
| `agentLabel`             |          | If set, the step wraps itself in `node(agentLabel) { }`. Omit if your stage already pins `agent { label 'macos' }` |

Returns the absolute path of the wrapped `.ipa`.

```groovy
stage('Wrap iOS') {
    agent { label 'macos' }
    steps {
        script {
            def wrappedIpa = intuneWrapIOS(
                inputIpa: "${WORKSPACE}/build/MyApp.ipa",
                outputDir: "${WORKSPACE}/wrapped",
                provisioningProfile: "${WORKSPACE}/profiles/MyApp.mobileprovision",
                signingCertificate: 'Apple Distribution: My Company (ABCDE12345)'
            )
            archiveArtifacts artifacts: 'wrapped/*.ipa'
        }
    }
}
```

The signing certificate and its private key must already be present in a
keychain accessible to the Jenkins agent user (import it ahead of time, e.g.
via a `security import` step or a provisioning script on the macOS agent).

## `intuneWrapAndroid`

Calls `Invoke-AppWrappingTool -InputPath <in> -OutputPath <out> [-KeyStorePath ... -KeyAlias ... -SigAlg ... -KeyStorePassword ... -KeyPassword ...] [-Verbose]`.

`inputApk` accepts either a `.apk` or a `.aab`. The Intune wrapping tool
itself only understands `.apk`, so when a `.aab` is passed, the step first
runs `bundletool build-apks --mode=universal` to produce a universal `.apk`,
unzips it, and wraps *that*. This intermediate file is written to a
per-run temp directory that's deleted after the step finishes.

| Parameter                          | Required | Description                                                                 |
|--------------------------------------|:--------:|-------------------------------------------------------------------------------|
| `inputApk`                            | ✅       | Path to the source `.apk` **or** `.aab` to wrap                              |
| `outputDir`                           | ✅       | Directory the wrapped `.apk` will be written to                              |
| `bundletoolPath`                      | if input is `.aab` | Path to bundletool's `.jar`, used to build a universal `.apk` from the bundle |
| `bundleKeyStorePath`                  |          | Keystore to sign the *interim* universal `.apk` with. Defaults to `keyStorePath`. If neither is set, bundletool uses its own default debug keystore (or produces an unsigned interim APK if none exists) — fine, since the Intune tool discards existing signing anyway |
| `bundleKeyAlias`                      |          | Defaults to `keyAlias`                                                       |
| `bundleKeyStorePasswordCredentialsId` |          | Defaults to `keyStorePasswordCredentialsId`                                  |
| `bundleKeyPasswordCredentialsId`      |          | Defaults to `keyPasswordCredentialsId`                                       |
| `keyStorePath`                        |          | Java keystore to re-sign the **final wrapped output** with. **Omit entirely if you don't want the wrapper to re-sign it.** |
| `keyAlias`                            | if re-signing | Signing key alias inside the keystore                                   |
| `keyStorePasswordCredentialsId`       | if re-signing | Jenkins **Secret text** credential ID holding the keystore password     |
| `keyPasswordCredentialsId`            |          | Jenkins Secret text credential ID for the key password (defaults to the keystore password credential) |
| `sigAlg`                              |          | Signature algorithm (default: `SHA256withRSA`)                               |
| `toolInstallDir`                      |          | Install dir of the tool (default: `C:\Program Files (x86)\Microsoft Intune Mobile Application Management\Android\App Wrapping Tool`) |
| `outputFileName`                      |          | Wrapped file name (default: `<input>-wrapped.apk`)                           |
| `verbose`                              |          | Passes `-Verbose` (default: `false`)                                         |
| `agentLabel`                           |          | If set, the step wraps itself in `node(agentLabel) { }`. Omit if your stage already pins `agent { label 'windows' }` |

Returns the absolute path of the wrapped `.apk`.

**Wrapping a `.apk` that's already signed:**

```groovy
stage('Wrap Android') {
    agent { label 'windows' }
    steps {
        script {
            def wrappedApk = intuneWrapAndroid(
                inputApk: "${WORKSPACE}\\build\\MyApp.apk",
                outputDir: "${WORKSPACE}\\wrapped",
                keyStorePath: 'C:\\keys\\myapp.keystore',
                keyAlias: 'myapp',
                keyStorePasswordCredentialsId: 'android-keystore-password',
                keyPasswordCredentialsId: 'android-key-password'
            )
            archiveArtifacts artifacts: 'wrapped\\*.apk'
        }
    }
}
```

**Wrapping a `.aab`:**

```groovy
stage('Wrap Android') {
    agent { label 'windows' }
    steps {
        script {
            def wrappedApk = intuneWrapAndroid(
                inputApk: "${WORKSPACE}\\build\\MyApp.aab",
                outputDir: "${WORKSPACE}\\wrapped",
                bundletoolPath: 'C:\\tools\\bundletool-all.jar',
                keyStorePath: 'C:\\keys\\myapp.keystore',
                keyAlias: 'myapp',
                keyStorePasswordCredentialsId: 'android-keystore-password'
            )
            archiveArtifacts artifacts: 'wrapped\\*.apk'
        }
    }
}
```

Passwords are **never** placed on the command line or logged. For the final
wrap they're pulled in via `withCredentials([string(...)])` and converted to
a PowerShell `SecureString` inside the process. For the bundletool
conversion step, `Invoke-AppWrappingTool` isn't involved yet — bundletool
takes plain passwords via `--ks-pass`/`--key-pass`, so those are instead
written to short-lived temp files (`--ks-pass=file:...`) inside the
per-run work directory and deleted immediately after bundletool exits.

## Setting up credentials (Android signing)

1. Manage Jenkins → Credentials → add a **Secret text** credential containing
   the keystore password (e.g. ID `android-keystore-password`).
2. Add a second Secret text credential for the key password if it differs
   from the keystore password (e.g. ID `android-key-password`).
3. Reference the credential IDs in the `keyStorePasswordCredentialsId` /
   `keyPasswordCredentialsId` parameters as shown above.

## Error handling

Both steps validate required parameters and fail fast (`IntuneWrapperException`)
before invoking the tool if:
- required parameters are missing,
- the input file doesn't have the expected extension,
- the pipeline is running on the wrong OS for that step,
- the wrapping tool binary/module can't be found,
- the input file, provisioning profile, or keystore doesn't exist,
- the underlying tool exits non-zero,
- the tool exits 0 but no output file was actually produced.

Each failure raises a clear message in the Jenkins console log rather than
failing with a raw shell/PowerShell exit code.

## Full example

See [`test/Jenkinsfile.example`](test/Jenkinsfile.example) for a complete
declarative pipeline that wraps both platforms in parallel stages.
