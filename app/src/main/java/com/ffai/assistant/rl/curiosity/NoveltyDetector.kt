package com.ffai.assistant.rl.curiosity

import kotlin.math.abs

/**
 * NoveltyDetector - Detecta estados novedosos usando hash counting.
 * Basado en episodic curiosity con pseudo-counts.
 */
class NoveltyDetector(private val featureDim: Int = 512) {
    
    companion object {
        const val HASH_SIZE = 10000
        const val SIMILARITY_THRESHOLD = 0.8f
        const val NOVELTY_BONUS = 0.5f
    }
    
    // Hash tables para conteo
    private val hashTable = IntArray(HASH_SIZE) { 0 }
    private val featureBank = mutableListOf<FloatArray>()
    
    // Estadísticas
    private var totalStates = 0
    private var novelStates = 0
    
    /**
     * Computa hash de features (SimHash simplificado).
     */
    private fun computeHash(features: FloatArray): Int {
        var hash = 0
        val step = features.size / 32
        
        for (i in 0 until 32) {
            val idx = i * step
            if (features.getOrElse(idx) { 0f } > 0) {
                hash = hash or (1 shl i)
            }
        }
        return hash % HASH_SIZE
    }
    
    /**
     * Detecta si estado es novedoso.
     */
    fun isNovel(features: FloatArray): Boolean {
        totalStates++
        val hash = computeHash(features)
        
        // Si hash no visto antes, es novedoso
        if (hashTable[hash] == 0) {
            hashTable[hash] = 1
            featureBank.add(features.copyOf())
            novelStates++
            return true
        }
        
        // Verificar similitud con estados previos
        val similar = findSimilar(features)
        return similar == null
    }
    
    /**
     * Calcula bonus de novedad.
     */
    fun computeNoveltyBonus(features: FloatArray): Float {
        val hash = computeHash(features)
        val visitCount = hashTable[hash]
        
        // Bonus decrece con visitas
        return NOVELTY_BONUS / kotlin.math.sqrt(visitCount.toFloat() + 1f)
    }
    
    /**
     * Encuentra estado similar en feature bank.
     */
    private fun findSimilar(features: FloatArray): FloatArray? {
        return featureBank.find { cosineSimilarity(features, it) > SIMILARITY_THRESHOLD }
    }
    
    /**
     * Similitud coseno.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dot += a[i] * b.getOrElse(i) { 0f }
            normA += a[i] * a[i]
            normB += b.getOrElse(i) { 0f } * b.getOrElse(i) { 0f }
        }
        
        return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB) + 1e-8f)
    }
    
    /**
     * Pseudo-count para estimar rareza.
     */
    fun pseudoCount(features: FloatArray): Float {
        val hash = computeHash(features)
        return hashTable[hash].toFloat()
    }
    
    fun reset() {
        hashTable.fill(0)
        featureBank.clear()
        totalStates = 0
        novelStates = 0
    }
    
    fun getStats() = NoveltyStats(
        totalStates = totalStates,
        novelStates = novelStates,
        noveltyRate = if (totalStates > 0) novelStates.toFloat() / totalStates else 0f,
        featureBankSize = featureBank.size
    )
}

data class NoveltyStats(
    val totalStates: Int,
    val novelStates: Int,
    val noveltyRate: Float,
    val featureBankSize: Int
)
