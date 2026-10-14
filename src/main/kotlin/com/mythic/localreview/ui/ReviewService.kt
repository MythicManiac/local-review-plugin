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
class ActiveReview(val store: ReviewStore, val git: GitCli, val changes: List<FileChange>) {
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

    private val listeners = CopyOnWriteArrayList<ReviewListener>()
    private val storeListener: () -> Unit = { fire(ReviewListener.Kind.DATA) }

    fun addListener(parent: Disposable, l: ReviewListener) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun fire(kind: ReviewListener.Kind) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) listeners.forEach { it.changed(kind) } }, ModalityState.any())
    }

    // ---- environment ----------------------------------------------------------------------------------------

    fun gitExe(): String = try {
        GitExecutableManager.getInstance().pathToGit
    } catch (e: Exception) {
        "git"
    }

    fun repository() = ReviewRepository(ReviewSettings.getInstance().storeRoot(), gitExe())

    fun gitRoots(): List<Path> =
        GitRepositoryManager.getInstance(project).repositories.map { it.root.toNioPath() }

    fun gitFor(root: Path) = GitCli(root, gitExe())

    private fun repoInfoOf(root: Path): RepoInfo {
        val g = gitFor(root)
        return RepoInfo(root.name, g.remoteUrl(), root.toString())
    }

    fun listReviews(): List<ReviewSummary> {
        val repo = repository()
        return gitRoots().flatMap { repo.list(repoInfoOf(it)) }.distinctBy { it.dir }.sortedByDescending { it.review.createdAt }
    }

    /** Finds the project git root a review belongs to. */
    private fun rootFor(store: ReviewStore): Path? {
        val r = store.review().repo
        val roots = gitRoots()
        return roots.firstOrNull { it.toString() == r.lastKnownPath }
            ?: roots.firstOrNull { repoInfoOf(it).let { info -> (info.remoteUrl != null && info.remoteUrl == r.remoteUrl) || info.name == r.name } }
    }

    // ---- lifecycle (call from a background thread) --------------------------------------------------------------

    fun open(summary: ReviewSummary) {
        val store = repository().open(summary)
        val root = rootFor(store) ?: error("This review belongs to repository '${store.review().repo.name}', which is not open in this project.")
        activate(store, root)
    }

    fun create(root: Path, title: String, baseRef: String, headRef: String, mergeBase: Boolean) {
        val git = gitFor(root)
        val store = repository().create(git, title, baseRef, headRef, mergeBase)
        activate(store, root)
    }

    fun update(): UpdateResult {
        val a = active ?: error("No active review")
        val result = ReviewUpdater.update(a.store, a.git)
        if (result.newRevision != null) activate(a.store, a.git.toplevel())
        return result
    }

    /** Recomputes the file list for the store's current revision (git diff) and publishes it. */
    internal fun activate(store: ReviewStore, root: Path) {
        val git = gitFor(root)
        val rev = store.review().current
        val changes = git.diffRaw(rev.effectiveBaseSha, rev.headSha).sortedBy { it.path }
        val previous = active
        if (previous != null && previous.store !== store) {
            previous.store.removeListener(storeListener)
            previous.store.close()
        }
        store.removeListener(storeListener)
        store.addListener(storeListener)
        active = ActiveReview(store, git, changes)
        remember(store.dir)
        store.problems.forEach { notify("Review data problem: $it", NotificationType.WARNING) }
        fire(ReviewListener.Kind.ACTIVE)
    }

    private fun projectKey() = project.basePath ?: project.name

    /** Persisted beside the reviews (atomic write), so it survives IDE crashes and forced kills. */
    private fun remember(dir: Path?) {
        try {
            repository().rememberLastOpened(projectKey(), dir)
        } catch (e: java.io.IOException) {
            notify("Could not remember the open review: ${e.message}", NotificationType.WARNING)
        }
    }

    fun close() {
        active?.store?.let { it.removeListener(storeListener); it.close() }
        active = null
        remember(null)
        fire(ReviewListener.Kind.ACTIVE)
    }

    /** Reopens the review that was active when the project was last used (background). */
    fun restoreLast() {
        val dir = repository().lastOpened(projectKey())?.normalize() ?: return
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
