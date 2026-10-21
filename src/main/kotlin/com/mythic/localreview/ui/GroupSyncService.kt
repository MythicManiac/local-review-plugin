package com.mythic.localreview.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.mythic.localreview.core.SyncResult
import com.mythic.localreview.store.ReviewGroups
import git4idea.config.GitExecutableManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Application-wide owner of the [ReviewGroups] (one shared committer per group, across all open projects) and of
 * background syncing: a debounced sync after local commits, a periodic pull for groups in use, and manual syncs.
 * Auto-sync only runs for groups that have a remote and have auto-sync enabled.
 */
@Service(Service.Level.APP)
class GroupSyncService : Disposable {
    data class Status(val at: Instant, val result: SyncResult)

    private val log = Logger.getInstance(GroupSyncService::class.java)
    private var groups: ReviewGroups? = null
    private var groupsKey = ""
    private val statuses = ConcurrentHashMap<String, Status>()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val inUse = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val failureShown = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<(String, SyncResult) -> Unit>()
    private val periodic: ScheduledFuture<*>

    init {
        periodic = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ inUse.toList().forEach { syncIfAuto(it) } }, PERIOD_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES)
    }

    fun gitExe(): String = try {
        GitExecutableManager.getInstance().pathToGit
    } catch (e: Exception) {
        "git"
    }

    /** The groups of the configured store folder. */
    @Synchronized
    fun groups(): ReviewGroups {
        val root = ReviewSettings.getInstance().storeRoot()
        val exe = gitExe()
        val key = "$root|$exe"
        if (groups == null || key != groupsKey) {
            groups?.close()
            groups = ReviewGroups(root, exe, onCommitted = { name -> scheduleSync(name) })
            groupsKey = key
        }
        return groups!!
    }

    fun status(group: String): Status? = statuses[group]

    fun isSyncing(group: String) = group in running

    fun addListener(parent: Disposable, l: (String, SyncResult) -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    /** Marks a group as one the user is working in: it gets an immediate sync and periodic pulls. */
    fun markInUse(group: String) {
        if (inUse.add(group)) {
            ApplicationManager.getApplication().executeOnPooledThread { syncIfAuto(group) }
        }
    }

    /** Debounced: many edits in a row result in a single sync shortly after the last one. */
    fun scheduleSync(group: String) {
        pending.compute(group) { _, old ->
            old?.cancel(false)
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                pending.remove(group)
                syncIfAuto(group)
            }, DEBOUNCE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun syncIfAuto(group: String) {
        val g = try { groups().group(group) } catch (e: Exception) { null } ?: return
        if (g.remoteUrl != null && g.autoSync) syncNow(group)
    }

    /** Runs a sync on the calling (background) thread. Concurrent syncs of one group collapse into one. */
    fun syncNow(group: String): SyncResult {
        if (!running.add(group)) return statuses[group]?.result ?: SyncResult.Ok(pulled = false, pushed = false)
        try {
            val r = try {
                groups().sync(group)
            } catch (e: Exception) {
                SyncResult.Failed(e.message ?: e.toString())
            }
            statuses[group] = Status(Instant.now(), r)
            report(group, r)
            listeners.forEach { it(group, r) }
            return r
        } finally {
            running.remove(group)
        }
    }

    private fun report(group: String, r: SyncResult) {
        when (r) {
            is SyncResult.Failed -> if (failureShown.add(group)) {
                notify("Sync of review group '$group' failed: ${r.message}\nYour reviews are safe on this machine; it will retry.", NotificationType.WARNING)
            }
            is SyncResult.Ok -> {
                failureShown.remove(group)
                if (r.conflicts.isNotEmpty()) {
                    notify(
                        "Sync of group '$group': two devices changed ${r.conflicts.joinToString(", ")} differently; the versions were merged by the review " +
                            "format's rules (the group created first is kept; other lines of the repository files are combined). No review data was lost.",
                        NotificationType.INFORMATION,
                    )
                }
            }
            SyncResult.NoRemote -> {}
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Local Diff Review").createNotification(message, type).notify(null)
    }

    override fun dispose() {
        periodic.cancel(false)
        pending.values.forEach { it.cancel(false) }
        groups?.close()
    }

    companion object {
        private const val DEBOUNCE_SECONDS = 15L
        private const val PERIOD_MINUTES = 5L
        fun getInstance(): GroupSyncService = ApplicationManager.getApplication().getService(GroupSyncService::class.java)
    }
}
