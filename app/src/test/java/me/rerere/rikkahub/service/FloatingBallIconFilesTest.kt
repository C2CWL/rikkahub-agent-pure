package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FloatingBallIconFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `each pick gets a new path so the settings flow emits`() {
        val dir = tmp.root
        val first = FloatingBallIconFiles.newFile(dir, 1000L)
        val second = FloatingBallIconFiles.newFile(dir, 2000L)
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertEquals(dir, first.parentFile)
    }

    @Test
    fun `only our icon files are recognised`() {
        val dir = tmp.root
        assertTrue(FloatingBallIconFiles.isIconFile(File(dir, "floating_ball_icon")))
        assertTrue(FloatingBallIconFiles.isIconFile(FloatingBallIconFiles.newFile(dir, 42L)))
        assertFalse(FloatingBallIconFiles.isIconFile(File(dir, "floating_ball_icon_backup")))
        assertFalse(FloatingBallIconFiles.isIconFile(File(dir, "avatar_123")))
    }

    @Test
    fun `stale files are everything but the kept icon`() {
        val dir = tmp.root
        val legacy = File(dir, "floating_ball_icon").apply { writeText("a") }
        val old = FloatingBallIconFiles.newFile(dir, 1L).apply { writeText("b") }
        val current = FloatingBallIconFiles.newFile(dir, 2L).apply { writeText("c") }
        val unrelated = File(dir, "settings.json").apply { writeText("d") }

        FloatingBallIconFiles.deleteStale(dir, keep = current)
        assertFalse(legacy.exists())
        assertFalse(old.exists())
        assertTrue(current.exists())
        assertTrue(unrelated.exists())

        // Reset removes the picked icon too.
        FloatingBallIconFiles.deleteStale(dir, keep = null)
        assertFalse(current.exists())
        assertTrue(unrelated.exists())
    }
}
