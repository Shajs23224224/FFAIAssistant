package com.ffai.assistant.model

import android.content.Context
import com.ffai.assistant.utils.Logger
import java.io.File

/**
 * ModelManager - Placeholders + Descarga + Offline
 */
class ModelManager(private val context: Context) {
    
    private val modelsDir = File(context.getExternalFilesDir(null), "models")
    val requiredModels = listOf("yolov8n_fp16.tflite", "dqn_dueling.tflite", "dqn_target.tflite",
        "ppo_actor.tflite", "ppo_critic.tflite", "sac_actor.tflite", "sac_q1.tflite", "sac_q2.tflite",
        "world_model_encoder.tflite", "world_model_transition.tflite", "world_model_reward.tflite",
        "transformer_policy.tflite", "icm_forward.tflite", "icm_inverse.tflite",
        "meta_controller.tflite", "sub_policies.tflite", "maml_meta.tflite")
    
    init { modelsDir.mkdirs() }
    
    fun initialize(): Boolean {
        requiredModels.forEach { name ->
            val file = File(modelsDir, name)
            if (!file.exists() || file.length() == 0L) {
                createPlaceholder(name)
            }
        }
        return true
    }
    
    private fun createPlaceholder(name: String) {
        File(modelsDir, "$name.placeholder").writeText("PLACEHOLDER")
    }
    
    fun isPlaceholder(name: String): Boolean {
        val file = File(modelsDir, name)
        val placeholder = File(modelsDir, "$name.placeholder")
        return placeholder.exists() || !file.exists() || file.length() < 1024
    }
    
    fun updateFromLocal(source: File, name: String): Boolean {
        if (!source.exists() || source.length() < 1024) return false
        val target = File(modelsDir, name)
        source.copyTo(target, overwrite = true)
        File(modelsDir, "$name.placeholder").delete()
        Logger.i("ModelManager", "Updated $name from local file")
        return true
    }
    
    fun getStatus() = requiredModels.associateWith { 
        ModelStatus(it, isPlaceholder(it), !isPlaceholder(it)) 
    }
}

data class ModelStatus(val name: String, val isPlaceholder: Boolean, val available: Boolean)
