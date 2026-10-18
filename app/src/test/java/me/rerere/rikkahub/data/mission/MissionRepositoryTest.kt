package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [MissionRepository]: event creation on `create`, state-machine
 * enforcement in `transition` (legal vs illegal edges, `completedAt` stamping on DONE,
 * clearing on departure), acceptance-checked persistence, artifact-count auto-increment,
 * event-trim past 500, and cascade delete.
 *
 * Backed by [FakeMissionDao] — no Room, no instrumentation, runs fast on the JVM.
 */
class MissionRepositoryTest {

    private fun repo(): Pair<FakeMissionDao, MissionRepository> {
        val dao = FakeMissionDao()
        return dao to MissionRepository(dao)
    }

    @Test
    fun `create persists an ACTIVE mission and logs a CREATED event`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(
            title = "Ship feature X",
            description = "Deliver the workbench mission module",
            acceptance = listOf("Tests pass", "Contract reviewed"),
            conversationId = "conv-1",
            assistantId = "asst-1",
        )

        // Entity persisted with ACTIVE status and all fields carried through.
        val row = dao.getById(mission.id)
        assertNotNull(row)
        assertEquals("Ship feature X", row!!.title)
        assertEquals(MissionStatus.ACTIVE.name, row.status)
        assertEquals("conv-1", row.conversationId)
        assertEquals(0, row.artifactCount)
        assertNull("completedAt should be null on creation", row.completedAt)
        assertTrue("acceptance JSON should encode the criteria", row.acceptanceCriteria.size == 2)

        // A CREATED event was logged.
        val events = dao.snapshotEvents(mission.id)
        assertEquals(1, events.size)
        assertEquals(MissionEventType.CREATED.name, events[0].type)
        assertTrue(events[0].title.contains("Ship feature X"))
    }

    @Test
    fun `transition DONE to ACTIVE is rejected by the state machine`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(title = "M1")

        // ACTIVE → REVIEW → DONE (both legal).
        assertTrue(repo.transition(mission.id, MissionStatus.REVIEW))
        assertTrue(repo.transition(mission.id, MissionStatus.DONE))
        assertEquals(MissionStatus.DONE.name, dao.getById(mission.id)!!.status)

        // DONE → ACTIVE is illegal; state must stay DONE.
        assertFalse(repo.transition(mission.id, MissionStatus.ACTIVE))
        assertEquals(MissionStatus.DONE.name, dao.getById(mission.id)!!.status)
    }

    @Test
    fun `transition REVIEW to DONE is legal and stamps completedAt`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(title = "M2")

        repo.transition(mission.id, MissionStatus.REVIEW)
        val before = dao.getById(mission.id)!!.completedAt
        assertNull("completedAt should be null before DONE", before)

        assertTrue(repo.transition(mission.id, MissionStatus.DONE))
        val after = dao.getById(mission.id)!!.completedAt
        assertNotNull("completedAt should be stamped on entering DONE", after)
        assertTrue(after!! > 0)

        // A STATUS_CHANGED event was logged for the transition.
        val statusEvents = dao.snapshotEvents(mission.id)
            .filter { it.type == MissionEventType.STATUS_CHANGED.name }
        assertTrue("at least one STATUS_CHANGED for REVIEW→DONE",
            statusEvents.any { it.title.contains("DONE") })
    }

    @Test
    fun `transition out of DONE clears completedAt`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(title = "M3")
        repo.transition(mission.id, MissionStatus.DONE)
        assertNotNull(dao.getById(mission.id)!!.completedAt)

        // DONE → ARCHIVED is the only legal exit from DONE; completedAt must be cleared.
        assertTrue(repo.transition(mission.id, MissionStatus.ARCHIVED))
        assertNull(
            "completedAt should be null after leaving DONE",
            dao.getById(mission.id)!!.completedAt,
        )
    }

    @Test
    fun `setAcceptanceChecked persists the checked indices`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(title = "M4", acceptance = listOf("A", "B", "C"))

        repo.setAcceptanceChecked(mission.id, listOf(0, 2))
        val row = dao.getById(mission.id)!!
        assertEquals(listOf(0, 2), row.acceptanceChecked)
    }

    @Test
    fun `attachArtifact increments artifactCount and logs an ARTIFACT event`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(title = "M5")

        repo.attachArtifact(mission.id, "/workspace/out.txt", "write_text_file", "first artifact")
        repo.attachArtifact(mission.id, "/workspace/out2.txt", "workspace_edit_file", null)

        val row = dao.getById(mission.id)!!
        assertEquals(2, row.artifactCount)

        val artifacts = dao.snapshotEvents(mission.id)
            .filter { it.type == MissionEventType.ARTIFACT.name }
        assertEquals(2, artifacts.size)
        assertEquals("/workspace/out.txt", artifacts[0].title)
    }

    @Test
    fun `logEvent trims to 500 when exceeding the cap`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(title = "M6")

        // Insert 550 NOTE events — each logEvent call trims internally.
        repeat(550) { i ->
            repo.logEvent(mission.id, MissionEventType.NOTE, title = "note-$i")
        }

        val events = dao.snapshotEvents(mission.id)
        assertEquals("events must be capped at 500", 500, events.size)
        // The 50 oldest should have been evicted (note-0 through note-49 gone).
        val titles = events.map { it.title }
        assertFalse("oldest events should be trimmed", titles.contains("note-0"))
        assertFalse("note-49 should be trimmed", titles.contains("note-49"))
        assertTrue("note-50 should survive", titles.contains("note-50"))
        assertTrue("newest event should survive", titles.contains("note-549"))
    }

    @Test
    fun `delete cascades — removes mission and all its events`() = runBlocking {
        val (dao, repo) = repo()
        val mission = repo.create(title = "M7")
        repo.logEvent(mission.id, MissionEventType.NOTE, title = "n1")
        repo.logEvent(mission.id, MissionEventType.MILESTONE, title = "m1")
        repo.attachArtifact(mission.id, "/p", "tool")
        assertEquals(3, dao.snapshotEvents(mission.id).size)

        repo.delete(mission.id)

        assertNull("mission row gone", dao.getById(mission.id))
        assertEquals("events cascade-deleted", 0, dao.snapshotEvents(mission.id).size)

        // The mission should also disappear from observeAll.
        val all = dao.observeAll().first()
        assertTrue(all.none { it.id == mission.id })
    }
}
