package com.ffai.assistant.rl

/**
 * AdvancedNeuralConfig - Configuración de arquitecturas neuronales avanzadas.
 * 
 * Todos los hyperparámetros optimizados para Samsung A21S (Mali-G52, 4GB RAM).
 */
object AdvancedNeuralConfig {
    
    // ============================================
    // WORLD MODEL (Dreamer)
    // ============================================
    object WorldModel {
        const val LATENT_DIM = 1024
        const val STOCHASTIC_DIM = 32
        const val DETERMINISTIC_DIM = 256
        const val ACTION_DIM = 15
        const val IMAGE_CHANNELS = 3
        const val IMAGE_HEIGHT = 96
        const val IMAGE_WIDTH = 160
        
        // Planning
        const val IMAGINATION_HORIZON = 15
        const val NUM_TRAJECTORIES = 50
        const val CEM_ITERATIONS = 5
        const val CEM_TOP_K = 10
        
        // RSSM
        const val RECURRENT_UNITS = 256
        const val HIDDEN_UNITS = 512
    }
    
    // ============================================
    // TRANSFORMER
    // ============================================
    object Transformer {
        const val SEQUENCE_LENGTH = 64
        const val STATE_DIM = 256
        const val EMBEDDING_DIM = 256
        const val NUM_HEADS = 8
        const val NUM_LAYERS = 4
        const val NUM_ACTIONS = 15
        const val FF_HIDDEN_DIM = 512
        const val DROPOUT_RATE = 0.1f
        
        // Attention
        const val ATTENTION_DROPOUT = 0.1f
        const val USE_CAUSAL_MASK = true
    }
    
    // ============================================
    // ICM (Curiosity)
    // ============================================
    object ICM {
        const val STATE_DIM = 256
        const val FEATURE_DIM = 512
        const val ACTION_DIM = 15
        
        // Rewards
        const val FORWARD_LOSS_WEIGHT = 0.5f
        const val INVERSE_LOSS_WEIGHT = 0.5f
        const val MAX_INTRINSIC_REWARD = 1.0f
        const val INTRINSIC_SCALE = 0.5f
        
        // Novelty
        const val NOVELTY_THRESHOLD = 0.7f
        const val NOVELTY_BONUS = 0.5f
    }
    
    // ============================================
    // INTRINSIC REWARD ENGINE
    // ============================================
    object IntrinsicReward {
        const val ICM_WEIGHT = 0.5f
        const val NOVELTY_WEIGHT = 0.3f
        const val COVERAGE_WEIGHT = 0.2f
        const val EMA_ALPHA = 0.01f
        const val EXTRINSIC_WEIGHT = 1.0f
        const val INTRINSIC_WEIGHT = 0.5f
    }
    
    // ============================================
    // HIERARCHICAL RL
    // ============================================
    object Hierarchical {
        const val NUM_GOALS = 7
        const val GOAL_DURATION = 30  // frames @ 30fps
        const val GOAL_EMBED_DIM = 64
        const val META_LR = 0.001f
        const val SUBPOLICY_LR = 0.0003f
        
        // HER
        const val HER_STRATEGY = "future"  // final, future, episode, random
        const val HER_PROBABILITY = 0.8f
        const val HER_K = 4  // future states to sample
    }
    
    // ============================================
    // MAML (Meta-Learning)
    // ============================================
    object MAML {
        const val META_LR = 0.001f
        const val INNER_LR = 0.01f
        const val INNER_STEPS = 5
        const val NUM_TASKS = 16
        const val STATE_DIM = 256
        const val ACTION_DIM = 15
        
        // Tasks
        const val NUM_WEAPONS = 8
        const val NUM_MAPS = 3
        const val NUM_BEHAVIORS = 4
        const val NUM_SITUATIONS = 6
        const val SUPPORT_SET_SIZE = 20
        const val QUERY_SET_SIZE = 10
    }
    
    // ============================================
    // FAST ADAPTATION
    // ============================================
    object FastAdaptation {
        const val ADAPTATION_THRESHOLD = 0.7f
        const val MIN_ADAPTATION_STEPS = 3
        const val MAX_ADAPTATION_STEPS = 10
        const val ADAPTATION_MEMORY_SIZE = 50
    }
    
    // ============================================
    // LATENCY TARGETS (A21S)
    // ============================================
    object LatencyTargets {
        const val WORLD_MODEL_ENCODE = 5L      // ms
        const val WORLD_MODEL_PLAN = 15L       // ms
        const val TRANSFORMER_INFERENCE = 8L   // ms
        const val ICM_INFERENCE = 3L           // ms
        const val HIERARCHICAL_DECISION = 5L   // ms
        const val MAML_ADAPTATION = 5L         // ms (offline)
        const val TOTAL_PIPELINE_MAX = 50L     // ms
    }
    
    // ============================================
    // MODEL FILES
    // ============================================
    object ModelFiles {
        // World Model
        const val WM_ENCODER = "world_model_encoder.tflite"
        const val WM_TRANSITION = "world_model_transition.tflite"
        const val WM_REWARD = "world_model_reward.tflite"
        const val WM_DECODER = "world_model_decoder.tflite"
        
        // Dreamer
        const val DREAMER_ACTOR = "dreamer_actor.tflite"
        const val DREAMER_CRITIC = "dreamer_critic.tflite"
        
        // Transformer
        const val TRANSFORMER_POLICY = "transformer_policy.tflite"
        
        // ICM
        const val ICM_FORWARD = "icm_forward.tflite"
        const val ICM_INVERSE = "icm_inverse.tflite"
        const val ICM_FEATURE = "icm_feature.tflite"
        
        // Hierarchical
        const val META_CONTROLLER = "meta_controller.tflite"
        const val SUB_POLICIES = "sub_policies.tflite"
        
        // MAML
        const val MAML_META = "maml_meta.tflite"
    }
    
    // ============================================
    // FEATURE FLAGS
    // ============================================
    object Features {
        const val ENABLE_WORLD_MODEL = true
        const val ENABLE_TRANSFORMER = true
        const val ENABLE_ICM = true
        const val ENABLE_HIERARCHICAL = true
        const val ENABLE_MAML = true
        
        const val ENABLE_GPU_DELEGATE = true
        const val ENABLE_XNNPACK = true
        const val ENABLE_FP16 = true
    }
    
    // ============================================
    // PERFORMANCE
    // ============================================
    object Performance {
        const val BATCH_SIZE = 32
        const val NUM_THREADS = 2
        const val MEMORY_POOL_SIZE = 100
        const val MAX_REPLAY_BUFFER = 100000
    }
}
