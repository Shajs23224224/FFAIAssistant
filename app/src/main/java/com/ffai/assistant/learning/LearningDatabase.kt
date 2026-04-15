package com.ffai.assistant.learning

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ffai.assistant.action.Action
import com.ffai.assistant.config.Constants
import com.ffai.assistant.core.EpisodeStats
import com.ffai.assistant.core.Experience
import com.ffai.assistant.perception.GameState
import java.nio.ByteBuffer

/**
 * Base de datos SQLite para persistir experiencias y estadísticas.
 */
class LearningDatabase(context: Context) : SQLiteOpenHelper(
    context,
    Constants.DB_NAME,
    null,
    Constants.DB_VERSION
) {
    
    override fun onCreate(db: SQLiteDatabase) {
        // Tabla de experiencias
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS experiences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER,
                state_health REAL,
                state_ammo REAL,
                state_enemy_present INTEGER,
                state_enemy_x REAL,
                state_enemy_y REAL,
                state_enemy_distance REAL,
                action_index INTEGER,
                action_name TEXT,
                reward REAL,
                next_state_health REAL,
                done INTEGER,
                priority REAL
            )
        """.trimIndent())
        
        // Tabla de estadísticas de episodios
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS episodes (
                episode_id INTEGER PRIMARY KEY,
                total_reward REAL,
                total_actions INTEGER,
                kills INTEGER,
                placement INTEGER,
                survival_time_ms INTEGER,
                damage_dealt REAL,
                start_time INTEGER,
                end_time INTEGER
            )
        """.trimIndent())
        
        // Índices para búsquedas rápidas
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_timestamp ON experiences(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_placement ON episodes(placement)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS experiences")
        db.execSQL("DROP TABLE IF EXISTS episodes")
        onCreate(db)
    }
    
    /**
     * Guarda una experiencia en la BD.
     */
    fun saveExperience(exp: Experience): Long {
        val values = ContentValues().apply {
            put("timestamp", exp.timestamp)
            put("state_health", exp.state.healthRatio)
            put("state_ammo", exp.state.ammoRatio)
            put("state_enemy_present", if (exp.state.enemyPresent) 1 else 0)
            put("state_enemy_x", exp.state.enemyX)
            put("state_enemy_y", exp.state.enemyY)
            put("state_enemy_distance", exp.state.enemyDistance)
            put("action_index", exp.action.type.index)
            put("action_name", exp.action.type.name)
            put("reward", exp.reward)
            put("next_state_health", exp.nextState.healthRatio)
            put("done", if (exp.done) 1 else 0)
            put("priority", exp.priority)
        }
        
        return writableDatabase.insert("experiences", null, values)
    }
    
    /**
     * Carga las N experiencias más recientes.
     */
    fun loadRecentExperiences(limit: Int): List<Experience> {
        val experiences = mutableListOf<Experience>()
        
        val cursor = readableDatabase.query(
            "experiences",
            null,
            null,
            null,
            null,
            null,
            "timestamp DESC",
            limit.toString()
        )
        
        cursor.use {
            while (it.moveToNext()) {
                val state = GameState(
                    healthRatio = it.getFloat(it.getColumnIndexOrThrow("state_health")),
                    ammoRatio = it.getFloat(it.getColumnIndexOrThrow("state_ammo")),
                    enemyPresent = it.getInt(it.getColumnIndexOrThrow("state_enemy_present")) == 1,
                    enemyX = it.getFloat(it.getColumnIndexOrThrow("state_enemy_x")),
                    enemyY = it.getFloat(it.getColumnIndexOrThrow("state_enemy_y")),
                    enemyDistance = it.getFloat(it.getColumnIndexOrThrow("state_enemy_distance"))
                )
                
                val action = Action(
                    com.ffai.assistant.action.ActionType.fromIndex(
                        it.getInt(it.getColumnIndexOrThrow("action_index"))
                    )
                )
                
                val nextState = GameState(
                    healthRatio = it.getFloat(it.getColumnIndexOrThrow("next_state_health"))
                )
                
                val exp = Experience(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    state = state,
                    action = action,
                    reward = it.getFloat(it.getColumnIndexOrThrow("reward")),
                    nextState = nextState,
                    done = it.getInt(it.getColumnIndexOrThrow("done")) == 1,
                    priority = it.getFloat(it.getColumnIndexOrThrow("priority")),
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                )
                
                experiences.add(exp)
            }
        }
        
        return experiences
    }
    
    /**
     * Guarda estadísticas de un episodio.
     */
    fun saveEpisodeStats(stats: EpisodeStats) {
        val values = ContentValues().apply {
            put("episode_id", stats.episodeId)
            put("total_reward", stats.totalReward)
            put("total_actions", stats.totalActions)
            put("kills", stats.kills)
            put("placement", stats.placement)
            put("survival_time_ms", stats.survivalTimeMs)
            put("damage_dealt", stats.damageDealt)
            put("start_time", stats.startTime)
            put("end_time", stats.endTime)
        }
        
        writableDatabase.insertWithOnConflict(
            "episodes",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
    
    /**
     * Obtiene estadísticas agregadas de todos los episodios.
     */
    fun getAggregateStats(): AggregateStats? {
        val cursor = readableDatabase.rawQuery("""
            SELECT 
                COUNT(*) as count,
                AVG(total_reward) as avg_reward,
                AVG(placement) as avg_placement,
                SUM(kills) as total_kills,
                AVG(survival_time_ms) as avg_survival
            FROM episodes
        """.trimIndent(), null)
        
        return cursor.use {
            if (it.moveToFirst()) {
                AggregateStats(
                    totalEpisodes = it.getInt(it.getColumnIndexOrThrow("count")),
                    averageReward = it.getFloat(it.getColumnIndexOrThrow("avg_reward")),
                    averagePlacement = it.getFloat(it.getColumnIndexOrThrow("avg_placement")),
                    totalKills = it.getInt(it.getColumnIndexOrThrow("total_kills")),
                    averageSurvivalTimeMs = it.getLong(it.getColumnIndexOrThrow("avg_survival"))
                )
            } else null
        }
    }
    
    /**
     * Elimina experiencias antiguas para liberar espacio.
     */
    fun cleanupOldExperiences(keepCount: Int = 5000) {
        writableDatabase.execSQL("""
            DELETE FROM experiences 
            WHERE id NOT IN (
                SELECT id FROM experiences 
                ORDER BY timestamp DESC 
                LIMIT $keepCount
            )
        """.trimIndent())
    }
    
    data class AggregateStats(
        val totalEpisodes: Int,
        val averageReward: Float,
        val averagePlacement: Float,
        val totalKills: Int,
        val averageSurvivalTimeMs: Long
    )
}
