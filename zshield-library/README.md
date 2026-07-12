# zshield-library

Jenkins Shared Library for running **zShield** actions against a `.nwproj`
project file. Must run on a **macOS** agent with the zShield CLI installed.

## ⚠️ About the CLI syntax

zShield's exact command-line flags aren't publicly documented, so this
library makes a single, minimal assumption and lets you control everything
else:

```
<zshieldPath> <action> "<nwprojPath>" [actionArgs[action]...] [extraArgs...]
```

e.g. `zshield prepare "/path/to/App.nwproj"` or
`zshield protect "/path/to/App.nwproj" --output "/path/to/out"`.

If your zShield CLI needs a different argument order, flags before the
project path, or anything else nonstandard, pass those via `actionArgs` /
`extraArgs` (see below) — nothing beyond `<action> <nwprojPath>` is
inferred. If the pattern above doesn't match your CLI at all, edit the
command-construction logic directly in `vars/zshieldRun.groovy` (it's
isolated to a few lines inside `runActions()`).

## Repository layout

```
zshield-library/
├── vars/
│   ├── zshieldRun.groovy       # global step: run one or more actions in one call
│   ├── zshieldUpgrade.groovy   # convenience: single 'upgrade' action
│   ├── zshieldPrepare.groovy   # convenience: single 'prepare' action
│   ├── zshieldProtect.groovy   # convenience: single 'protect' action
│   └── zshieldAnalyze.groovy   # convenience: single 'analyze' action
├── test/
│   └── Jenkinsfile.example
└── README.md
```

`zshieldRun` is fully self-contained (validation, command construction,
execution) in its `vars/zshieldRun.groovy` file — there's no `src/` class
hierarchy. The four convenience steps just delegate to `zshieldRun`.
Failures are raised with Jenkins' built-in `error()` step, which fails the
build with a clear message in the console log.

Register this repo in Jenkins under **Manage Jenkins → System → Global
Pipeline Libraries** (or load per-Jenkinsfile with `@Library`).

## `zshieldRun` (multi-action)

| Parameter          | Required | Description                                                                 |
|----------------------|:--------:|-------------------------------------------------------------------------------|
| `nwprojPath`          | ✅       | Path to the `.nwproj` file                                                   |
| `actions`             | ✅       | List of one or more of `'upgrade'`, `'prepare'`, `'protect'`, `'analyze'`, run in the given order |
| `actionArgs`          |          | `Map<String, List<String>>` of extra CLI args per action, e.g. `[protect: ['--output', '/path']]` |
| `extraArgs`           |          | `List<String>` of extra CLI args appended to **every** action call            |
| `zshieldPath`         |          | Path to the `zshield` executable (default: `zshield`, resolved via `PATH`)   |
| `workingDirectory`    |          | Directory to invoke the CLI from (default: the `.nwproj` file's parent dir)  |
| `failFast`            |          | Stop at the first failing action (default: `true`). If `false`, all requested actions still run, then a single exception summarizing every failure is thrown at the end |
| `agentLabel`          |          | If set, wraps itself in `node(agentLabel) { }`. Omit if your stage already pins `agent { label 'macos' }` |

Returns `Map<String, Integer>` — action name → exit code, for every action
that ran.

```groovy
stage('zShield') {
    agent { label 'macos' }
    steps {
        script {
            def results = zshieldRun(
                nwprojPath: "${WORKSPACE}/App.nwproj",
                actions: ['prepare', 'protect'],
                actionArgs: [
                    protect: ['--output', "${WORKSPACE}/protected"]
                ]
            )
            echo "Exit codes: ${results}"
        }
    }
}
```

## Single-action convenience steps

`zshieldUpgrade`, `zshieldPrepare`, `zshieldProtect`, `zshieldAnalyze` each
take the same parameters as `zshieldRun` (minus `actions`, which is fixed)
and return the single exit code (`Integer`) of that action.

```groovy
zshieldUpgrade(nwprojPath: '/Users/ci/projects/App.nwproj')
zshieldPrepare(nwprojPath: '/Users/ci/projects/App.nwproj')
zshieldProtect(
    nwprojPath: '/Users/ci/projects/App.nwproj',
    extraArgs: ['--output', '/Users/ci/projects/protected']
)
zshieldAnalyze(nwprojPath: '/Users/ci/projects/App.nwproj')
```

## Error handling

- Fails fast before invoking the CLI if: `nwprojPath` is missing or isn't a
  `.nwproj` file, `actions` is missing/empty/contains an unsupported value,
  the pipeline isn't running on macOS, the `zshield` executable can't be
  found, or the `.nwproj` file doesn't exist.
- Each action's exit code is checked; a non-zero exit fails the build via
  Jenkins' `error()` step, naming the action and exit code (unless
  `failFast: false`, in which case all actions run and a single summary
  error is raised at the end).

## Full example

See [`test/Jenkinsfile.example`](test/Jenkinsfile.example).
