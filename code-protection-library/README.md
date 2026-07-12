# code-protection-library

A single Groovy helper, `codeProtect`, that lets a pipeline pick **which**
app-protection tool to run at build time — Microsoft Intune App Wrapping
Tool, zShield, or Guardsquare (iXGuard/DexGuard) — instead of hardcoding
one into the Jenkinsfile.

This library contains **no protection logic of its own**. It's a thin
router that forwards your config to the matching step from whichever of
these libraries you already have:

- [`intune-wrap-library`](../intune-wrap-library) → `intuneWrapIOS` / `intuneWrapAndroid`
- [`zshield-library`](../zshield-library) → `zshieldRun`
- [`guardsquare-library`](../guardsquare-library) → `ixguardProtect` / `dexguardProtect`

All three must be loaded in the same Jenkinsfile (via `@Library`) alongside
this one, or the underlying step `codeProtect` tries to call won't exist.

## Repository layout

```
code-protection-library/
├── vars/
│   └── codeProtect.groovy   # global step: dispatches to the selected tool
├── test/
│   └── Jenkinsfile.example  # full pipeline with a runtime tool/platform picker
└── README.md
```

## `codeProtect`

| Parameter    | Required | Description                                                                 |
|----------------|:--------:|-------------------------------------------------------------------------------|
| `tool`          | ✅       | One of `'intune'`, `'zshield'`, `'guardsquare'`                              |
| `platform`      | for `intune`/`guardsquare` | One of `'ios'`, `'android'`. Not used for `'zshield'` (a `.nwproj` isn't platform-specific) — omit it or pass either value |
| *(everything else)* |    | Passed straight through, unchanged, to the step it routes to                  |

Everything besides `tool`/`platform` is forwarded verbatim, so use whatever
parameter names the **target step** expects:

| `tool` + `platform`             | Routes to           | Parameters                                                      |
|-------------------------------------|----------------------|----------------------------------------------------------------------|
| `intune` + `ios`                     | `intuneWrapIOS`       | `inputIpa`, `outputDir`, `provisioningProfile`, `signingCertificate`, ... |
| `intune` + `android`                 | `intuneWrapAndroid`   | `inputApk` (`.apk` or `.aab`), `outputDir`, `bundletoolPath`, `keyStorePath`, ... |
| `zshield` *(platform ignored)*       | `zshieldRun`          | `nwprojPath`, `actions`, `actionArgs`, `extraArgs`, ...               |
| `guardsquare` + `ios`                | `ixguardProtect`      | `inputIpa`, `outputIpa`, `configFile`, ...                            |
| `guardsquare` + `android`            | `dexguardProtect`     | `inputApk`, `outputApk`, `configFile`, `dexguardPath`, ...             |

Returns whatever the underlying step returns — a `String` path for the
wrapping/protection steps, or `Map<String, Integer>` (action → exit code)
for `zshield`.

```groovy
// iXGuard, chosen explicitly
codeProtect(
    tool: 'guardsquare',
    platform: 'ios',
    inputIpa: "${WORKSPACE}/build/MyApp.ipa",
    outputIpa: "${WORKSPACE}/protected/MyApp-protected.ipa",
    configFile: "${WORKSPACE}/ixguard.yml"
)

// zShield — platform doesn't matter here
codeProtect(
    tool: 'zshield',
    nwprojPath: "${WORKSPACE}/App.nwproj",
    actions: ['prepare', 'protect']
)
```

## Building a "Code Protection" stage with a runtime picker

The real value of `codeProtect` is letting the *pipeline caller* decide the
tool — e.g. via a build `choice` parameter — without the Jenkinsfile
branching on tool-specific logic everywhere. See
[`test/Jenkinsfile.example`](test/Jenkinsfile.example) for the full
pattern:

1. Declare `choice` parameters for `PROTECTION_TOOL` (`intune` /
   `guardsquare` / `zshield`) and `PLATFORM` (`ios` / `android`).
2. In the `Code Protection` stage, pick the agent label based on the
   selection — Intune's Android path needs Windows, everything else here
   needs macOS.
3. A small helper function maps the chosen tool/platform onto the config
   keys that specific step expects (they differ — see the table above —
   since this library doesn't try to force a single unified parameter
   schema onto three unrelated tools), then calls `codeProtect(config)`.

```groovy
stage('Code Protection') {
    agent {
        label (params.PROTECTION_TOOL == 'intune' && params.PLATFORM == 'android') ? 'windows' : 'macos'
    }
    steps {
        script {
            def result = runCodeProtectionStage(
                tool: params.PROTECTION_TOOL,
                platform: params.PLATFORM,
                inputFile: params.INPUT_FILE
            )
            echo "Code protection result: ${result}"
        }
    }
}
```

## Error handling

Fails fast via Jenkins' `error()` step if `tool` is missing/unrecognized,
or if `platform` is missing/unrecognized when `tool` is `intune` or
`guardsquare`. Beyond that, all validation (required files, OS checks,
etc.) is whatever the routed-to step already does — `codeProtect` doesn't
duplicate it.
