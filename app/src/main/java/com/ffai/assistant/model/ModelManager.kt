package com.ffai.assistant.model

import android.content.Context
import com.ffai.assistant.utils.Logger
import java.io.File

/**
 * ModelManager - Gestiona modelos TFLite embebidos en assets
 */
class ModelManager(private val context: Context) {
    
    val requiredModels = listOf("yolov8n_fp16.tflite", "dqn_dueling.tflite", "dqn_target.tflite",
        "ppo_actor.tflite", "ppo_critic.tflite", "sac_actor.tflite", "sac_q1.tflite", "sac_q2.tflite",
        "world_model_encoder.tflite", "world_model_transition.tflite", "world_model_reward.tflite",
        "transformer_policy.tflite", "icm_forward.tflite", "icm_inverse.tflite", "icm_feature.tflite",
        "meta_controller.tflite", "sub_policies.tflite", "maml_meta.tflite")
    
    fun initialize(): Boolean {
        // Verificar que todos los modelos existen en assets
        val assetManager = context.assets
        requiredModels.forEach { name ->
            try {
                assetManager.openFd(name).close()
                Logger.i("ModelManager", "Modelo encontrado en assets: $name")
            } catch (e: Exception) {
                Logger.e("ModelManager", "Modelo NO encontrado en assets: $name", e)
            }
        }
        return true
    }
    
    fun isPlaceholder(name: String): Boolean {
        // En assets, verificar que el archivo existe y tiene tamaño válido
        return try {
            val fd = context.assets.openFd(name)
            val valid = fd.declaredLength >= 1024
            fd.close()
            !valid
        } catch (e: Exception) {
            true // Si no existe, es placeholder
        }
    }
    
    fun getStatus() = requiredModels.associateWith { 
        ModelStatus(it, isPlaceholder(it), !isPlaceholder(it)) 
    }
}

data class ModelStatus(val name: String, val isPlaceholder: Boolean, val available: Boolean)
