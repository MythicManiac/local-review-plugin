# AGENTS.md

Kotlin JetBrains plugin; see `docs/DESIGN.md` (decisions) and `docs/PLAN.md` (status).

* Build with `JAVA_HOME` = PyCharm's JBR (JDK 25); system `java` is 8. Use `./gradlew`, never a global Gradle.
* Package `com.mythic.localreview`: `core` (no IDE imports — keep it unit-testable), `git`, `ui`.
* Persisted state is user data: every write goes through `ReviewStore` (atomic temp+rename). Never delete review
  files; use tombstones (`deletedAt`). Persisted formats are versioned (`schemaVersion`); readers ignore unknown keys.
* Platform UI-kit reuse (`com.intellij.collaboration.*`) only inside `ui.platformreuse`; it is not a stable API.
* Verify IDE API shapes against the installed distribution/sources, not memory (IDE build 262 is newer than most docs).
* Update `docs/PLAN.md` status and commit at the end of each phase.
