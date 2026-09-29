package com.zohaib.batterymonitor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val TYPE_CHARGE = "charge"
const val TYPE_DISCHARGE = "discharge"
const val PKG_SCREEN_OFF = "screen_off"
/** Screen on, but no app in front: home screen, lock screen, notification shade. */
const val PKG_SCREEN_ON_OTHER = "screen_on_other"

@Entity(tableName = "session")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val start: Long,
    val startPct: Int,
    val end: Long? = null,
    val endPct: Int? = null,
    /** Charge reached 100%, or discharge reached 1%. */
    val complete: Boolean = false,
    /** Average accuracy of this session's estimates, set when it closes. */
    val accuracy: Double? = null,
)

/** One 1% change. [windowMs] is how long that 1% took. */
@Entity(tableName = "step", indices = [Index("sessionId"), Index("ts")])
data class Step(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val pct: Int,
    val ts: Long,
    val windowMs: Long,
    val topApp: String? = null,
)

/** Foreground time of one app during one discharge step. */
@Entity(tableName = "step_app", indices = [Index("stepId")])
data class StepApp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stepId: Long,
    val pkg: String,
    val ms: Long,
)

/**
 * A saved prediction. Rates are ms per 1%. For charging, [rateLow] applies below 80%
 * and [rateHigh] from 80% to 100%; for discharging only [rateLow] is used.
 */
@Entity(tableName = "estimate", indices = [Index("sessionId")])
data class Estimate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val label: String,
    val madeAt: Long,
    val madeAtPct: Int,
    val rateLow: Double,
    val rateHigh: Double,
)

data class AppDrain(val pkg: String, val ms: Long, val pct: Double)

@Dao
interface BatteryDao {
    @Insert suspend fun insert(s: Session): Long
    @Update suspend fun update(s: Session)
    @Insert suspend fun insert(s: Step): Long
    @Insert suspend fun insertApps(a: List<StepApp>)
    @Insert suspend fun insert(e: Estimate): Long

    @Query("SELECT * FROM session WHERE `end` IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun openSession(): Session?

    @Query("SELECT * FROM session WHERE `end` IS NULL ORDER BY id DESC LIMIT 1")
    fun openSessionFlow(): Flow<Session?>

    @Query("SELECT * FROM session WHERE id = :id")
    fun sessionFlow(id: Long): Flow<Session?>

    @Query("SELECT * FROM session WHERE type = :type ORDER BY start DESC")
    fun sessionsFlow(type: String): Flow<List<Session>>

    @Query("SELECT * FROM step WHERE sessionId = :id ORDER BY ts")
    suspend fun steps(id: Long): List<Step>

    @Query("SELECT * FROM step WHERE sessionId = :id ORDER BY ts")
    fun stepsFlow(id: Long): Flow<List<Step>>

    @Query("SELECT a.* FROM step_app a JOIN step s ON a.stepId = s.id WHERE s.sessionId = :id")
    fun stepAppsFlow(id: Long): Flow<List<StepApp>>

    @Query("SELECT * FROM estimate WHERE sessionId = :id ORDER BY madeAt")
    suspend fun estimates(id: Long): List<Estimate>

    @Query("SELECT * FROM estimate WHERE sessionId = :id ORDER BY madeAt")
    fun estimatesFlow(id: Long): Flow<List<Estimate>>

    @Query(
        """SELECT a.pkg AS pkg, SUM(a.ms) AS ms,
           SUM(CAST(a.ms AS REAL) / CASE WHEN s.windowMs > 0 THEN s.windowMs ELSE 1 END) AS pct
           FROM step_app a JOIN step s ON a.stepId = s.id
           WHERE s.ts >= :since GROUP BY a.pkg ORDER BY pct DESC"""
    )
    fun appDrainSince(since: Long): Flow<List<AppDrain>>

    @Query(
        """SELECT a.pkg AS pkg, SUM(a.ms) AS ms,
           SUM(CAST(a.ms AS REAL) / CASE WHEN s.windowMs > 0 THEN s.windowMs ELSE 1 END) AS pct
           FROM step_app a JOIN step s ON a.stepId = s.id
           WHERE s.sessionId = :id GROUP BY a.pkg ORDER BY pct DESC"""
    )
    fun appDrainForSession(id: Long): Flow<List<AppDrain>>

    @Query("DELETE FROM step_app WHERE stepId IN (SELECT id FROM step WHERE ts < :before)")
    suspend fun purgeStepApps(before: Long)
    @Query("DELETE FROM step WHERE ts < :before")
    suspend fun purgeSteps(before: Long)
    @Query("DELETE FROM estimate WHERE sessionId IN (SELECT id FROM session WHERE `end` IS NOT NULL AND `end` < :before)")
    suspend fun purgeEstimates(before: Long)
    @Query("DELETE FROM session WHERE `end` IS NOT NULL AND `end` < :before")
    suspend fun purgeSessions(before: Long)
}

@Database(entities = [Session::class, Step::class, StepApp::class, Estimate::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): BatteryDao

    companion object {
        fun build(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "battery.db").build()
    }
}
