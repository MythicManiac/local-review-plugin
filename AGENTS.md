# AGENTS.md

Kotlin JetBrains plugin; see `docs/DESIGN.md` (decisions) and `docs/PLAN.md` (status).

* Build with `JAVA_HOME` = PyCharm's JBR (JDK 25); system `java` is 8. Use `./gradlew`, never a global Gradle.
* Package `com.mythic.localreview`: `core` (no IDE imports — keep it unit-testable), `git`, `ui`.
* A review group = one git repo shared by all its stores through one `GitCommitter` (never create a second committer for a group dir; get it from `ReviewGroups.committer`). File writes go through `GitCommitter.exclusive`.
* Persisted state is user data: every write goes through `ReviewStore` (atomic temp+rename). Never delete review
  files; use tombstones (`deletedAt`). Persisted formats are versioned (`schemaVersion`); readers ignore unknown keys.
* Do not depend on `com.intellij.collaboration.*` (review UI kit is deprecated/unstable; see docs/DESIGN.md). Use stable platform APIs.
* Smoke the UI with `./gradlew runIde -PdemoProject=<repo>`; headless diff-viewer behaviour is covered by `DiffIntegrationTest`.
* Verify IDE API shapes against the installed distribution/sources, not memory (IDE build 262 is newer than most docs).
* Update `docs/PLAN.md` status and commit at the end of each phase.
* Marketplace listing text lives in `docs/marketplace/` and `CHANGELOG.md` (both are build inputs); release steps in `docs/PUBLISHING.md`. Run `gradlew verifyPlugin` before releases and keep it free of deprecated/experimental API usage.
* The plugin name is "Local Diff Review" (the Marketplace name "Local Review" is taken); the plugin ID `com.mythic.localreview` is permanent once published.
