package app.orchard.tv.wallpaper

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XiaoheiSetTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private var day = "2026-10-29"

    @Before
    fun setUp() {
        app.getSharedPreferences("aerial_set", 0).edit().clear().commit()
        XiaoheiSet.today = { day }
    }

    @After
    fun tearDown() {
        XiaoheiSet.today = { java.time.LocalDate.now().toString() }
    }

    @Test
    fun startsWithOneFilmAndNothingNewTheSameDay() {
        assertEquals(1, XiaoheiSet.films(app).size)
        assertFalse(XiaoheiSet.due(app))
        assertNull(XiaoheiSet.takeDaily(app))
    }

    @Test
    fun aNewDayBringsOneNewFilmFirst() {
        val first = XiaoheiSet.films(app).single()
        day = "2026-10-30"
        assertTrue(XiaoheiSet.due(app))
        val new = XiaoheiSet.takeDaily(app)
        assertNotNull(new)
        new!!
        assertEquals(listOf(new.id, first.id), XiaoheiSet.films(app).map { it.id })
        // Only one a day.
        assertNull(XiaoheiSet.takeDaily(app))
    }

    @Test
    fun theNewFilmIsTheOneChosenAhead() {
        XiaoheiSet.films(app)
        day = "2026-10-30"
        val ahead = XiaoheiSet.nextNew(app)
        assertEquals(ahead.id, XiaoheiSet.takeDaily(app)?.id)
    }

    @Test
    fun keepsAtMostThreeNewestFirstWithoutRepeats() {
        XiaoheiSet.films(app)
        val added = (1..4).map { XiaoheiSet.addNew(app).id }
        val kept = XiaoheiSet.films(app).map { it.id }
        assertEquals(XiaoheiSet.MAX, kept.size)
        assertEquals(added.reversed().take(XiaoheiSet.MAX), kept)
        assertEquals(kept.size, kept.toSet().size)
    }

    @Test
    fun playlistStartsAtTheCurrentFilmAndGoesRound() {
        XiaoheiSet.films(app)
        XiaoheiSet.addNew(app)
        XiaoheiSet.addNew(app)
        val set = XiaoheiSet.films(app).map { it.id }
        val list = XiaoheiSet.playlist(app, set[1]).map { it.id }
        assertEquals(listOf(set[1], set[2], set[0]), list)
    }

    @Test
    fun aFilmFromOutsideTheSetJoinsIt() {
        XiaoheiSet.films(app)
        val other = XiaoheiFilms.all(app).first { it.id !in XiaoheiSet.films(app).map { f -> f.id } }
        val list = XiaoheiSet.playlist(app, other.id)
        assertEquals(other.id, list.first().id)
        assertTrue(XiaoheiSet.films(app).any { it.id == other.id })
    }
}
