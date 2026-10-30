package dev.opentunnel.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun normalizesReleaseTags() {
        assertEquals("0.3.4", Version.normalize("v0.3.4"))
        assertEquals("0.3.4", Version.normalize("V0.3.4"))
        assertEquals("0.3.4", Version.normalize("v0.3.4-beta.1"))
        assertEquals("10.0.1", Version.normalize("v10.0.1+build"))
        assertEquals("0.3.4", Version.normalize(" 0.3.4 "))
    }

    @Test
    fun rejectsJunk() {
        assertNull(Version.normalize(""))
        assertNull(Version.normalize("v"))
        assertNull(Version.normalize("release"))
        assertNull(Version.normalize("v0.3.x"))
        assertNull(Version.normalize("v0.-1.2"))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Version.isNewer("v0.3.4", "0.3.3"))
        assertTrue(Version.isNewer("0.10.0", "0.9.9"))
        assertTrue(Version.isNewer("1.0", "0.9.9"))
        assertTrue(Version.isNewer("1.0.1", "1"))
        assertFalse(Version.isNewer("1.0.0", "1"))
        assertFalse(Version.isNewer("0.3.3", "0.3.3"))
        assertFalse(Version.isNewer("v0.3.2", "0.3.3"))
        assertFalse(Version.isNewer("junk", "0.3.3"))
    }
}
