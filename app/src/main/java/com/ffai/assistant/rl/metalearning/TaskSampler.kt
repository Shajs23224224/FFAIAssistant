package com.ffai.assistant.rl.metalearning

import com.ffai.assistant.utils.Logger

/**
 * TaskSampler - Samplea tareas para meta-learning.
 * 
 * Distribución de tareas:
 * - Weapon-specific: M4, AK47, AWM, SMG, etc.
 * - Map-specific: Purgatory, Bermuda, Kalahari
 * - Enemy behaviors: Aggressive, Defensive, Camper, Rusher
 * - Tactical situations: Low health, High ground, Open field, Urban
 */
class TaskSampler {
    
    companion object {
        const val TAG = "TaskSampler"
        
        // Task counts
        const val NUM_WEAPONS = 8
        const val NUM_MAPS = 3
        const val NUM_BEHAVIORS = 4
        const val NUM_SITUATIONS = 6
        
        const val SUPPORT_SET_SIZE = 20
        const val QUERY_SET_SIZE = 10
    }
    
    // Registro de tareas
    private val weaponTasks = (1..NUM_WEAPONS).map { id ->
        Task(id, "Weapon_$id", TaskType.WEAPON_SPECIFIC, 1.0f + id * 0.1f)
    }
    
    private val mapTasks = (1..NUM_MAPS).map { id ->
        Task(NUM_WEAPONS + id, "Map_$id", TaskType.MAP_SPECIFIC)
    }
    
    private val behaviorTasks = (1..NUM_BEHAVIORS).map { id ->
        Task(NUM_WEAPONS + NUM_MAPS + id, "Behavior_$id", TaskType.ENEMY_TYPE)
    }
    
    private val situationTasks = (1..NUM_SITUATIONS).map { id ->
        Task(NUM_WEAPONS + NUM_MAPS + NUM_BEHAVIORS + id, "Situation_$id", TaskType.TACTICAL_SITUATION)
    }
    
    private val allTasks = weaponTasks + mapTasks + behaviorTasks + situationTasks
    
    // Distribución de sampling
    private val taskWeights = allTasks.map { 1.0f }.toMutableList()
    
    /**
     * Samplea batch de tareas para meta-training.
     */
    fun sampleTaskBatch(batchSize: Int): List<Task> {
        val sampled = mutableListOf<Task>()
        val available = allTasks.indices.toMutableList()
        
        repeat(batchSize) {
            if (available.isEmpty()) return@repeat
            
            // Weighted sampling
            val totalWeight = available.sumOf { taskWeights[it].toDouble() }
            var random = kotlin.random.Random.nextDouble() * totalWeight
            
            var selected = available.first()
            for (idx in available) {
                random -= taskWeights[idx]
                if (random <= 0) {
                    selected = idx
                    break
                }
            }
            
            sampled.add(allTasks[selected])
            available.remove(selected)
            
            // Downweight selected task
            taskWeights[selected] *= 0.9f
        }
        
        Logger.d(TAG, "Sampled ${sampled.size} tasks: ${sampled.map { it.name }}")
        return sampled
    }
    
    /**
     * Genera support/query split para una tarea.
     */
    fun generateTaskData(task: Task): TaskData {
        // Generar datos sintéticos (placeholder)
        val supportSet = generateExperiences(task, SUPPORT_SET_SIZE)
        val querySet = generateExperiences(task, QUERY_SET_SIZE)
        
        return TaskData(task, supportSet, querySet)
    }
    
    /**
     * Genera experiencias para una tarea.
     */
    private fun generateExperiences(task: Task, count: Int): List<MAMLExperience> {
        return (0 until count).map { i ->
            val state = generateTaskSpecificState(task, i)
            val action = (0 until 15).random()
            val reward = if (kotlin.random.Random.nextFloat() > 0.3f) 1f else 0f
            
            MAMLExperience(
                state = state,
                action = action,
                reward = reward,
                nextState = state // Simplified
            )
        }
    }
    
    /**
     * Genera estado específico de task.
     */
    private fun generateTaskSpecificState(task: Task, seed: Int): FloatArray {
        val base = FloatArray(256) { kotlin.random.Random.nextFloat() }
        
        // Modificar según tipo de task
        return when (task.type) {
            TaskType.WEAPON_SPECIFIC -> {
                // Features específicas de arma
                base[0] = (task.id % NUM_WEAPONS) / NUM_WEAPONS.toFloat()
                base
            }
            TaskType.MAP_SPECIFIC -> {
                // Features de mapa
                base[1] = ((task.id - NUM_WEAPONS) % NUM_MAPS) / NUM_MAPS.toFloat()
                base
            }
            TaskType.ENEMY_TYPE -> {
                base[2] = kotlin.random.Random.nextFloat()
                base
            }
            TaskType.TACTICAL_SITUATION -> {
                base[3] = kotlin.random.Random.nextFloat()
                base
            }
        }
    }
    
    /**
     * Obtiene tarea por contexto actual.
     */
    fun getTaskForContext(context: ContextChange): Task? {
        return when (context) {
            is ContextChange.NewWeapon -> weaponTasks.getOrNull(context.weaponId % NUM_WEAPONS)
            is ContextChange.NewMap -> mapTasks.getOrNull(context.mapId % NUM_MAPS)
            is ContextChange.NewEnemyBehavior -> behaviorTasks.randomOrNull()
            is ContextChange.LowHealth -> situationTasks.randomOrNull()
        }
    }
    
    /**
     * Actualiza pesos de tareas basado en performance.
     */
    fun updateTaskWeights(results: List<AdaptationResult>) {
        results.forEach { result ->
            val taskIdx = allTasks.indexOfFirst { it.id == result.taskId }
            if (taskIdx >= 0) {
                // Aumentar peso si performance buena
                taskWeights[taskIdx] *= (1f + result.queryAccuracy * 0.1f)
                taskWeights[taskIdx] = taskWeights[taskIdx].coerceIn(0.1f, 5f)
            }
        }
    }
    
    /**
     * Reset distribution.
     */
    fun resetDistribution() {
        taskWeights.fill(1.0f)
    }
    
    fun getAllTasks(): List<Task> = allTasks
    fun getTaskCount(): Int = allTasks.size
}

data class TaskData(
    val task: Task,
    val supportSet: List<MAMLExperience>,
    val querySet: List<MAMLExperience>
)
