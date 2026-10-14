# Local Review

JetBrains IDE plugin for reviewing **local** git changes (e.g. AI-authored branches) before they go to remote PR review.
Pick two comparison points, review the diff in the IDE's diff viewer, annotate code, track progress, export a report,
and feed recurring findings into `AGENTS.md`.

* Design & decisions: [docs/DESIGN.md](docs/DESIGN.md)
* Plan & status: [docs/PLAN.md](docs/PLAN.md)

## Build & run

Requires a JDK 21+ (the JBR bundled with PyCharm works) — the `java` on `PATH` may be older, so point `JAVA_HOME` at it:

```
set JAVA_HOME=C:\Program Files\JetBrains\PyCharm 2025.1.1.1\jbr
gradlew.bat test        # unit tests
gradlew.bat runIde      # sandbox IDE with the plugin
gradlew.bat buildPlugin # distributable zip in build/distributions
```

The IDE to build against is set by `ideLocalPath` in `gradle.properties` (default: the local PyCharm install).
