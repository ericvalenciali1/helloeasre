# guardsquare-library

Jenkins Shared Library for protecting mobile apps with **Guardsquare**
tooling — **iXGuard** for iOS and **DexGuard** for Android. Both are
run on a **macOS** agent.

| Step               | Protects | Underlying tool | Verified syntax? |
|----------------------|----------|-------------------|:-------------------:|
| `ixguardProtect`     | `.ipa`   | `ixguard` CLI      | ✅ confirmed via Guardsquare's own CI docs |
| `dexguardProtect`    | `.apk`   | `dexguard` (standalone) | ⚠️ inferred from ProGuard-standalone conventions — see below |

## ⚠️ About DexGuard's CLI syntax

`ixguardProtect`'s command is confirmed directly from Guardsquare's own
published CI examples:

```
ixguard -c=<config.yml> -d=<workDir> -f -o=<outputIpa> <inputIpa>
```

DexGuard's **standalone** CLI (as opposed to its more commonly-used Gradle
plugin) isn't publicly documented in the same detail — it ships to paying
customers with the DexGuard download. `dexguardProtect` assumes DexGuard
follows the same standalone invocation convention as ProGuard, the
open-source tool it's built on:

```
java -jar <dexguardPath> -injars <inputApk> -outjars <outputApk> @<configFile> [extraArgs]
```

If your actual DexGuard CLI differs, you can either:
- pass extra/replacement flags via `extraArgs`,
- pass `commandOverride` with the full command you actually run (inputApk /
  outputApk / configFile are still validated to exist beforehand), or
- edit `DexGuardRunner.buildCommand()` in
  `src/org/example/guardsquare/DexGuardRunner.groovy` directly — the
  command construction is isolated to a few lines.

## Repository layout

```
guardsquare-library/
├── vars/
│   ├── ixguardProtect.groovy    # global step: protect an .ipa with iXGuard
│   └── dexguardProtect.groovy   # global step: protect an .apk with DexGuard
├── src/org/example/guardsquare/
│   ├── IXGuardRunner.groovy
│   ├── DexGuardRunner.groovy
│   └── GuardsquareException.groovy
├── test/
│   └── Jenkinsfile.example
└── README.md
```

Register this repo in Jenkins under **Manage Jenkins → System → Global
Pipeline Libraries** (or load per-Jenkinsfile with `@Library`).

## `ixguardProtect`

| Parameter     | Required | Description                                                              |
|-----------------|:--------:|-----------------------------------------------------------------------------|
| `inputIpa`       | ✅       | Path to the source `.ipa` to protect                                       |
| `outputIpa`      | ✅       | Path for the protected `.ipa` (may equal `inputIpa` to protect in place)   |
| `configFile`     | ✅       | Path to the `ixguard.yml` configuration file                               |
| `workDir`        |          | Directory where iXGuard writes `mapping.yml` / `protectionreport.html` (default: `outputIpa`'s parent directory) |
| `force`          |          | Pass `-f` to overwrite an existing output file (default: `true`)           |
| `ixguardPath`    |          | Path to the `ixguard` executable (default: `ixguard` on `PATH`)            |
| `extraArgs`      |          | `List<String>` of additional CLI args appended to the command              |
| `agentLabel`     |          | If set, wraps itself in `node(agentLabel) { }`. Omit if your stage already pins `agent { label 'macos' }` |

Returns the absolute path of the protected `.ipa`.

```groovy
stage('Protect iOS') {
    agent { label 'macos' }
    steps {
        script {
            def protectedIpa = ixguardProtect(
                inputIpa: "${WORKSPACE}/build/MyApp.ipa",
                outputIpa: "${WORKSPACE}/protected/MyApp-protected.ipa",
                configFile: "${WORKSPACE}/ixguard.yml"
            )
            archiveArtifacts artifacts: 'protected/*.ipa'
        }
    }
}
```

## `dexguardProtect`

| Parameter          | Required | Description                                                              |
|-----------------------|:--------:|-----------------------------------------------------------------------------|
| `inputApk`             | ✅       | Path to the source `.apk` to protect                                       |
| `outputApk`            | ✅       | Path for the protected `.apk`                                              |
| `configFile`           | ✅       | Path to the DexGuard config (e.g. `dexguard-project.pro`), applied via `@configFile` |
| `dexguardPath`         | ✅       | Path to `dexguard.jar`, or to a DexGuard launcher executable               |
| `javaPath`             |          | `java` executable to use when `dexguardPath` is a `.jar` (default: `java` on `PATH`) |
| `workingDirectory`     |          | Directory to invoke the CLI from (default: `outputApk`'s parent directory) |
| `extraArgs`            |          | `List<String>` of additional CLI args appended to the built command        |
| `commandOverride`      |          | `List<String>` full command to run instead of the built one (see above)    |
| `agentLabel`           |          | If set, wraps itself in `node(agentLabel) { }`. Omit if your stage already pins `agent { label 'macos' }` |

Returns the absolute path of the protected `.apk`.

```groovy
stage('Protect Android') {
    agent { label 'macos' }
    steps {
        script {
            def protectedApk = dexguardProtect(
                inputApk: "${WORKSPACE}/build/MyApp.apk",
                outputApk: "${WORKSPACE}/protected/MyApp-protected.apk",
                configFile: "${WORKSPACE}/dexguard-project.pro",
                dexguardPath: '/Applications/DexGuard/lib/dexguard.jar'
            )
            archiveArtifacts artifacts: 'protected/*.apk'
        }
    }
}
```

## Error handling

Both steps validate required parameters and fail fast before invoking the
tool if: required parameters are missing, `inputIpa`/`outputIpa` or
`inputApk`/`outputApk` don't have the expected extension, the pipeline
isn't running on macOS, the tool executable/jar can't be found, the input
file or config file doesn't exist, the underlying tool exits non-zero, or
the tool exits `0` but no output file was actually produced.

## Full example

See [`test/Jenkinsfile.example`](test/Jenkinsfile.example) for a complete
pipeline protecting both platforms in parallel stages.
