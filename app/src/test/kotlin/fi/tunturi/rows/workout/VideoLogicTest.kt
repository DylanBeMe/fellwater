package fi.tunturi.rows.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceSmootherTest {

    @Test
    fun `steady pace passes through`() {
        val smoother = PaceSmoother()
        smoother.add(120.0, 0)
        assertEquals(120.0, smoother.add(120.0, 1_000)!!, 1e-9)
    }

    @Test
    fun `averages speed not pace`() {
        // 100 s and 150 s per 500 m: speeds 5 and 3.33 m/s, mean 4.17 m/s = 120 s/500 m.
        val smoother = PaceSmoother()
        smoother.add(100.0, 0)
        assertEquals(120.0, smoother.add(150.0, 500)!!, 1e-9)
    }

    @Test
    fun `old samples fall out of the window`() {
        val smoother = PaceSmoother(windowMs = 5_000)
        smoother.add(200.0, 0)
        assertEquals(100.0, smoother.add(100.0, 6_000)!!, 1e-9)
    }

    @Test
    fun `missing pace is ignored and an empty window gives null`() {
        val smoother = PaceSmoother(windowMs = 5_000)
        assertNull(smoother.add(null, 0))
        smoother.add(120.0, 1_000)
        assertEquals(120.0, smoother.add(0.0, 2_000)!!, 1e-9)
        assertNull(smoother.add(null, 10_000))
    }
}

class YouTubeLinkTest {

    @Test
    fun `recognises the usual link shapes`() {
        val id = "aqz-KE-bpKQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=abc123",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://m.youtube.com/watch?feature=share&v=$id",
            id,
        ).forEach { assertEquals(it, id, youTubeIdFrom(it)) }
    }

    @Test
    fun `other links are not youtube`() {
        assertNull(youTubeIdFrom("https://example.com/video.mp4"))
        assertNull(youTubeIdFrom(""))
    }

    @Test
    fun `finds the link inside shared text`() {
        assertEquals(
            "https://youtu.be/aqz-KE-bpKQ?si=x",
            linkIn("Big Buck Bunny https://youtu.be/aqz-KE-bpKQ?si=x"),
        )
        assertNull(linkIn("no link here"))
    }
}
