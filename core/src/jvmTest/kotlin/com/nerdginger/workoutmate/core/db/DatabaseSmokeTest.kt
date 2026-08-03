package com.nerdginger.workoutmate.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.nerdginger.workoutmate.db.WorkoutDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves the whole storage chain against real SQLite: SQLDelight generates
 * from the `.sq` files, the schema applies, and queries round-trip.
 *
 * This runs locally with no Android SDK and no emulator, which is the entire
 * reason storage is SQLDelight rather than Room. Every query added from here on
 * gets a test at this level.
 */
class DatabaseSmokeTest {

    private fun openDb(): WorkoutDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorkoutDb.Schema.create(driver)
        return WorkoutDb(driver)
    }

    @Test
    fun `schema applies and settings round-trip`() {
        val db = openDb()
        val settings = db.settingQueries

        assertNull(settings.get("units").executeAsOneOrNull())

        settings.upsert(key = "units", value_ = "lb", updated_at = 1_000L)
        assertEquals("lb", settings.get("units").executeAsOne())
    }

    @Test
    fun `upsert overwrites rather than failing on the primary key`() {
        val db = openDb()
        val settings = db.settingQueries

        settings.upsert(key = "units", value_ = "lb", updated_at = 1_000L)
        settings.upsert(key = "units", value_ = "kg", updated_at = 2_000L)

        val rows = settings.getAll().executeAsList()
        assertEquals(1, rows.size, "upsert must not create a second row")
        assertEquals("kg", rows.single().value_)
        assertEquals(2_000L, rows.single().updated_at)
    }

    @Test
    fun `delete removes only the named key`() {
        val db = openDb()
        val settings = db.settingQueries

        settings.upsert(key = "units", value_ = "lb", updated_at = 1L)
        settings.upsert(key = "restDefaultSec", value_ = "120", updated_at = 1L)
        settings.delete("units")

        assertNull(settings.get("units").executeAsOneOrNull())
        assertEquals("120", settings.get("restDefaultSec").executeAsOne())
    }
}
