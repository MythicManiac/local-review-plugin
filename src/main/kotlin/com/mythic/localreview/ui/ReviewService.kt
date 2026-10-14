package com.mythic.localreview.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.mythic.localreview.core.ChangeStatus
import com.mythic.localreview.core.Comment
import com.mythic.localreview.core.FileChange
import com.mythic.localreview.core.FileProgress
import com.mythic.localreview.core.GitCli
import com.mythic.localreview.core.GitException
import com.mythic.localreview.core.GroupInfo
import com.mythic.localreview.core.ReviewGroups
import com.mythic.localreview.core.SyncResult
import com.mythic.localreview.core.RepoInfo
import com.mythic.localreview.core.ReviewRepository
import com.mythic.localreview.core.ReviewStore
import com.mythic.localreview.core.ReviewSummary
import com.mythic.localreview.core.ReviewUpdater
import com.mythic.localreview.core.UpdateResult
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name

/** The review currently open in this project plus its computed file list. */
class ActiveReview(
    val store: ReviewStore,
    val git: GitCli,
    val changes: List<FileChange>,
    /** Group the review lives in (null only for reviews opened from outside any group, e.g. tests). */
    val group: String? = null,
    /** Set when the file list could not be computed (e.g. commits from another device are not present locally). */
    val problem: String? = null,
) {
    val changesByPath: Map<String, FileChange> = changes.associateBy { it.path }

    /** The file diff (if any) whose side-path matches this comment's anchor. */
    fun fileOf(c: Comment): FileChange? {
        val a = c.anchor
        val p = a.path ?: return null
        return changes.firstOrNull {
            when (a.side) {
                com.mythic.localreview.core.Side.HEAD -> it.newPath == p
                com.mythic.localreview.core.Side.BASE -> it.oldPath == p
            }
        }
    }

    /** True if [path]'s file is currently viewed (progress fingerprint still matches the file's diff). */
    fun isViewed(change: FileChange, p: FileProgress? = store.progress(change.path)): Boolean =
        p?.viewedFingerprint != null && p.viewedFingerprint == change.fingerprint

    fun isStale(change: FileChange, p: FileProgress? = store.progress(change.path)): Boolean =
        p?.viewedFingerprint != null && p.viewedFingerprint != change.fingerprint
}

fun interface ReviewListener {
    fun changed(kind: Kind)
    enum class Kind { ACTIVE, DATA }
}

@Service(Service.Level.PROJECT)
class ReviewService(private val project: Project) : Disposable {
    @Volatile
    var active: ActiveReview? = null
        private set

    /** Cached info of the active review's group (read off the EDT; the header renders from this). */
    @Volatile
    var groupInfo: GroupInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<ReviewListener>()
    private val storeListener: () -> Unit = { fire(ReviewListener.Kind.DATA) }

    init {
        GroupSyncService.getInstance().addListener(this) { group, result ->
            val a = active
            if (a != null && a.group == group) {
                if (result is SyncResult.Ok && result.pulled) {
                    ApplicationManager.getApplication().executeOnPooledThread { reloadActive() }
                } else {
                    fire(ReviewListener.Kind.DATA) // sync status line changed
                }
            }
        }
    }

    fun addListener(parent: Disposable, l: ReviewListener) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun fire(kind: ReviewListener.Kind) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) listeners.forEach { it.changed(kind) } }, ModalityState.any())
    }

    // ---- environment ----------------------------------------------------------------------------------------

    fun groups(): ReviewGroups = GroupSyncService.getInstance().groups()

    fun gitExe(): String = GroupSyncService.getInstance().gitExe()

    fun gitRoots(): List<Path> =
        GitRepositoryManager.getInstance(project).repositories.map { it.root.toNioPath() }

    fun gitFor(root: Path) = GitCli(root, gitExe())

    fun repoInfoOf(root: Path): RepoInfo {
        val g = gitFor(root)
        return RepoInfo(root.name, g.remoteUrl(), root.toString())
    }

    /** Reviews of this project's repositories across all groups. */
    fun listReviews(): List<ReviewSummary> {
        val groups = groups()
        return gitRoots().flatMap { groups.listReviews(repoInfoOf(it)) }.distinctBy { it.dir }.sortedByDescending { it.review.createdAt }
    }

    /** Finds the project git root a review belongs to. */
    private fun rootFor(store: ReviewStore): Path? {
        val r = store.review().repo
        val roots = gitRoots()
        return roots.firstOrNull { it.toString() == r.lastKnownPath }
            ?: roots.firstOrNull { ReviewRepository.sameRepo(repoInfoOf(it), r) }
    }

    // ---- lifecycle (call from a background thread) --------------------------------------------------------------

    fun open(summary: ReviewSummary) {
        val groups = groups()
        val g = groups.group(summary.group) ?: error("Review group '${summary.group}' no longer exists.")
        val store = groups.repository(g).open(summary)
        val root = rootFor(store) ?: error("This review belongs to repository '${store.review().repo.name}', which is not open in this project.")
        activate(store, root)
    }

    fun create(root: Path, title: String, baseRef: String, headRef: String, mergeBase: Boolean, groupName: String) {
        val groups = groups()
        val g = groups.group(groupName) ?: error("Review group '$groupName' does not exist.")
        val git = gitFor(root)
        val store = groups.repository(g).create(git, title, baseRef, headRef, mergeBase)
        groups.rememberGroup(store.review().repo, groupName)
        activate(store, root)
    }

    fun update(): UpdateResult {
        val a = active ?: error("No active review")
        val result = ReviewUpdater.update(a.store, a.git)
        if (result.newRevision != null) activate(a.store, a.git.toplevel())
        return result
    }

    /** Re-reads the active review from disk after a sync pulled changes from another device. */
    fun reloadActive() {
        val a = active ?: return
        val before = a.store.review().current
        try {
            a.store.reload()
        } catch (e: Exception) {
            notify("Could not reload review after sync: ${e.message}", NotificationType.WARNING)
            return
        }
        if (a.store.review().current != before) activate(a.store, a.git.toplevel())
    }

    /** Re-reads the active group's remote/auto-sync settings (after they were edited) and refreshes the views. */
    fun refreshGroup() {
        val a = active ?: return
        groupInfo = a.group?.let { groups().group(it) }
        fire(ReviewListener.Kind.DATA)
    }

    /** Recomputes the file list for the store's current revision (git diff) and publishes it. */
    internal fun activate(store: ReviewStore, root: Path) {
        val git = gitFor(root)
        val rev = store.review().current
        var problem: String? = null
        val changes = try {
            git.diffRaw(rev.effectiveBaseSha, rev.headSha).sortedBy { it.path }
        } catch (e: GitException) {
            problem = "Commits ${rev.effectiveBaseSha.take(8)}..${rev.headSha.take(8)} are not in this repository " +
                "(review synced from another device?). Fetch the branch, then run Update Review."
            emptyList()
        }
        val previous = active
        if (previous != null && previous.store !== store) {
            previous.store.removeListener(storeListener)
            previous.store.close()
        }
        store.removeListener(storeListener)
        store.addListener(storeListener)
        val groups = groups()
        val groupName = groups.groupOf(store.dir)
        active = ActiveReview(store, git, changes, groupName, problem)
        groupInfo = groupName?.let { groups.group(it) }
        remember(store.dir)
        groupName?.let { GroupSyncService.getInstance().markInUse(it) }
        store.problems.forEach { notify("Review data problem: $it", NotificationType.WARNING) }
        closeDiffTab()
        fire(ReviewListener.Kind.ACTIVE)
    }

    /** The open diff tab shows an older revision/review; close it rather than show stale code with hidden comments. */
    private fun closeDiffTab() {
        ApplicationManager.getApplication().invokeLater({ com.mythic.localreview.ui.diff.ReviewDiff.close(project) }, ModalityState.any())
    }

    private fun projectKey() = project.basePath ?: project.name

    /** Persisted beside the reviews (atomic write), so it survives IDE crashes and forced kills. */
    private fun remember(dir: Path?) {
        try {
            groups().rememberLastOpened(projectKey(), dir)
        } catch (e: java.io.IOException) {
            notify("Could not remember the open review: ${e.message}", NotificationType.WARNING)
        }
    }

    fun close() {
        active?.store?.let { it.removeListener(storeListener); it.close() }
        active = null
        groupInfo = null
        remember(null)
        closeDiffTab()
        fire(ReviewListener.Kind.ACTIVE)
    }

    /** Reopens the review that was active when the project was last used (background). */
    fun restoreLast() {
        val dir = groups().lastOpened(projectKey())?.normalize() ?: return
        val summary = listReviews().firstOrNull { it.dir.normalize() == dir } ?: return
        try {
            open(summary)
        } catch (e: Exception) {
            notify("Could not reopen review '${summary.review.title}': ${e.message}", NotificationType.WARNING)
        }
    }

    /** Asks all views to re-render from current state (e.g. after a UI-only filter changed). */
    fun refreshUi() = fire(ReviewListener.Kind.DATA)

    fun notify(message: String, type: NotificationType = NotificationType.INFORMATION, vararg actions: Pair<String, () -> Unit>) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Local Review").createNotification(message, type)
        for ((label, run) in actions) {
            n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(label) { run() })
        }
        n.notify(project)
    }

    override fun dispose() {
        active?.store?.let { it.removeListener(storeListener); it.close() }
    }

    companion object {
        fun getInstance(project: Project): ReviewService = project.getService(ReviewService::class.java)
    }
}

val ChangeStatus.letter: String
    get() = when (this) {
        ChangeStatus.ADDED -> "A"
        ChangeStatus.MODIFIED -> "M"
        ChangeStatus.DELETED -> "D"
        ChangeStatus.RENAMED -> "R"
        ChangeStatus.COPIED -> "C"
        ChangeStatus.TYPE_CHANGED -> "T"
    }
