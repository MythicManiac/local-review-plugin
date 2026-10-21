package com.mythic.localreview.store

import com.mythic.localreview.core.GitCli
import com.mythic.localreview.core.TestRepo
import com.mythic.localreview.core.tempDir
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnchorRange
import com.mythic.localreview.format.Event
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.Snapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private val machines = AtomicInteger()

/** A store root of one "machine" (its own device id), as a second computer would have. */
fun machineGroups(root: Path = tempDir(), clock: () -> Long = System::currentTimeMillis) =
    ReviewGroups(root, machine = "test-machine-${machines.incrementAndGet()}", clock = clock)

/** A project repository with a feature branch, as the source of reviews. */
fun project(name: String = "proj"): TestRepo {
    val parent = Files.createTempDirectory("lr-proj")
    val repo = TestRepo(parent.resolve(name).also { Files.createDirectories(it) })
    repo.write("a.txt", "one\ntwo\n")
    repo.commit("base")
    repo.branch("feature")
    repo.write("a.txt", "one\ntwo\nthree\n")
    repo.commit("work")
    return repo
}

fun newReview(groups: ReviewGroups, group: String, repo: TestRepo, title: String = "R", now: Long = System.currentTimeMillis()): OpenReview {
    val g = groups.reviewGroup(groups.group(group)!!)
    g.prepare()
    return g.create(ReviewFactory.newMeta(repo.git, title, "master", "feature", ReviewMode.MERGE_BASE, g.deviceId, now))
}

fun commentOn(pin: Pin, id: String, body: String, line: Int = 3, text: String = "three") = Event.AnnotationCreated(
    id, Kind.QUESTION, emptyList(), body,
    Anchor.Lines("a.txt", null, pin, listOf(AnchorRange(Side.NEW, line, line, Snapshot(listOf(text), listOf("two"), emptyList())))),
)

fun GitCli.status(): String = run("status", "--porcelain")
