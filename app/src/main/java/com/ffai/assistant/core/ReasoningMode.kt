package com.ffai.assistant.core

/**
 * Modos de razonamiento disponibles para el sistema de IA.
 * Determinan la profundidad y velocidad de procesamiento.
 */
enum class ReasoningMode {
    SHORT,   // Modo rápido/instinto (< 8ms)
    MEDIUM,  // Modo táctico estándar (8-20ms)
    LONG     // Modo estratégico profundo (> 20ms)
}
