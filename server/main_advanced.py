#!/usr/bin/env python3
"""
Free Fire AI Server - Advanced Version
Oracle Cloud Infrastructure (OCI) Optimized

Features:
- YOLOv8 para detección de UI y objetos
- Agente RL con PPO para decisiones tácticas
- WebSocket con compresión gzip
- Redis para cache de sesiones
"""

import asyncio
import base64
import gzip
import io
import json
import logging
import os
import time
from dataclasses import dataclass
from typing import Optional, Dict, List, Tuple

import numpy as np
import redis.asyncio as redis
import torch
import torch.nn as nn
from PIL import Image
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

# Configurar logging estructurado
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# ==================== CONFIGURACIÓN ====================

@dataclass
class ServerConfig:
    """Configuración del servidor."""
    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6379")
    MODEL_PATH: str = os.getenv("MODEL_PATH", "./models/ff_rl_v1.pt")
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    MAX_FRAME_SIZE: int = 1024 * 1024  # 1MB max
    SESSION_TIMEOUT: int = 300  # 5 minutos

config = ServerConfig()

# ==================== MODELO DE IA ====================

class TacticalNN(nn.Module):
    """Red neuronal para decisiones tácticas."""
    
    def __init__(self, input_size: int = 128, hidden_size: int = 256, num_actions: int = 8):
        super().__init__()
        self.fc1 = nn.Linear(input_size, hidden_size)
        self.fc2 = nn.Linear(hidden_size, hidden_size)
        self.fc3 = nn.Linear(hidden_size, hidden_size // 2)
        self.policy = nn.Linear(hidden_size // 2, num_actions)
        self.value = nn.Linear(hidden_size // 2, 1)
        self.dropout = nn.Dropout(0.2)
        
    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        x = torch.relu(self.fc1(x))
        x = self.dropout(x)
        x = torch.relu(self.fc2(x))
        x = self.dropout(x)
        x = torch.relu(self.fc3(x))
        
        policy_logits = self.policy(x)
        value = self.value(x)
        
        return policy_logits, value

class AdvancedFreeFireAI:
    """IA avanzada con YOLO + RL."""
    
    def __init__(self):
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.frame_count = 0
        self.session_memory: Dict[str, List[dict]] = {}
        
        # Cargar modelo RL
        self._init_model()
        
        # Mapeo de acciones
        self.action_map = {
            0: ("HOLD", 0, 0, 100),
            1: ("SHOOT", 1200, 800, 100),
            2: ("AIM", 1200, 600, 200),
            3: ("MOVE_FORWARD", 960, 500, 300),
            4: ("MOVE_BACKWARD", 960, 900, 300),
            5: ("MOVE_LEFT", 700, 700, 300),
            6: ("MOVE_RIGHT", 1220, 700, 300),
            7: ("RELOAD", 1200, 900, 1500),
            8: ("HEAL", 200, 800, 500),
            9: ("JUMP", 960, 700, 100),
        }
        
        logger.info(f"IA avanzada inicializada en {self.device}")
    
    def _init_model(self):
        """Inicializar o crear modelo."""
        self.model = TacticalNN().to(self.device)
        
        if os.path.exists(config.MODEL_PATH):
            try:
                checkpoint = torch.load(config.MODEL_PATH, map_location=self.device)
                self.model.load_state_dict(checkpoint['model_state_dict'])
                logger.info("Modelo cargado exitosamente")
            except Exception as e:
                logger.warning(f"No se pudo cargar modelo: {e}, usando inicialización aleatoria")
        else:
            logger.info("Modelo no encontrado, usando inicialización aleatoria")
        
        self.model.eval()
    
    def detect_ui_elements(self, image: np.ndarray) -> dict:
        """Detectar elementos UI del juego."""
        height, width = image.shape[:2]
        
        # Simulación de detección (en producción usar YOLOv8)
        # Aquí analizarías zonas específicas de la pantalla
        
        # Zonas típicas de Free Fire (coordenadas normalizadas 0-1)
        health_zone = image[int(height*0.85):int(height*0.95), int(width*0.02):int(width*0.25)]
        ammo_zone = image[int(height*0.85):int(height*0.95), int(width*0.75):int(width*0.98)]
        enemy_zone = image[int(height*0.1):int(height*0.6), int(width*0.3):int(width*0.7)]
        
        # Análisis simple de color (mejorar con YOLO)
        health_ratio = self._analyze_health_bar(health_zone)
        ammo_ratio = self._analyze_ammo(ammo_zone)
        enemy_present = self._detect_enemy_simple(enemy_zone)
        
        return {
            "health_ratio": health_ratio,
            "ammo_ratio": ammo_ratio,
            "enemy_present": enemy_present,
            "in_safe_zone": True,  # Placeholder
            "items_nearby": []
        }
    
    def _analyze_health_bar(self, zone: np.ndarray) -> float:
        """Analizar barra de vida por color."""
        # Verificar color verde/rojo típico de barras de vida
        hsv = self._to_hsv(zone)
        # Rango verde para vida
        green_mask = (hsv[:, :, 0] >= 35) & (hsv[:, :, 0] <= 85) & (hsv[:, :, 1] >= 50)
        green_ratio = np.sum(green_mask) / green_mask.size
        return min(green_ratio * 2.5, 1.0)  # Escalar aproximado
    
    def _analyze_ammo(self, zone: np.ndarray) -> float:
        """Analizar munición por texto/blanco."""
        # Simplificado - detectar números blancos
        gray = np.mean(zone, axis=2)
        bright_ratio = np.sum(gray > 200) / gray.size
        return min(bright_ratio * 3, 1.0)
    
    def _detect_enemy_simple(self, zone: np.ndarray) -> bool:
        """Detección simple de enemigos (placeholder para YOLO)."""
        # En producción: YOLOv8.detect(zone, classes=['person'])
        hsv = self._to_hsv(zone)
        # Detectar colores de ropa típicos
        red_mask = (hsv[:, :, 0] < 20) | (hsv[:, :, 0] > 160)
        red_ratio = np.sum(red_mask) / red_mask.size
        return red_ratio > 0.15
    
    def _to_hsv(self, image: np.ndarray) -> np.ndarray:
        """Convertir a HSV."""
        import cv2
        return cv2.cvtColor(image, cv2.COLOR_RGB2HSV)
    
    def process_frame(self, session_id: str, image: np.ndarray, client_state: dict) -> dict:
        """Procesar frame y decidir acción."""
        self.frame_count += 1
        
        # 1. Detectar estado del juego
        game_state = self.detect_ui_elements(image)
        
        # 2. Crear vector de estado
        state_vector = self._create_state_vector(game_state, client_state)
        
        # 3. Inferencia con modelo RL
        with torch.no_grad():
            state_tensor = torch.FloatTensor(state_vector).unsqueeze(0).to(self.device)
            policy_logits, value = self.model(state_tensor)
            
            # Muestrear acción
            probs = torch.softmax(policy_logits, dim=-1)
            action_idx = torch.multinomial(probs, 1).item()
            confidence = probs[0][action_idx].item()
        
        # 4. Mapear a acción
        action_name, x, y, duration = self.action_map.get(action_idx, ("HOLD", 0, 0, 100))
        
        # 5. Ajustar por contexto (heurísticas)
        action_name, x, y, duration = self._apply_heuristics(
            action_name, x, y, duration, game_state
        )
        
        # 6. Guardar en memoria de sesión
        self._update_session_memory(session_id, {
            "frame": self.frame_count,
            "state": game_state,
            "action": action_name,
            "confidence": confidence
        })
        
        return {
            "type": "action",
            "action": action_name,
            "confidence": round(confidence, 3),
            "coordinates": {"x": x, "y": y},
            "duration": duration,
            "game_state": game_state,
            "timestamp": time.time()
        }
    
    def _create_state_vector(self, game_state: dict, client_state: dict) -> np.ndarray:
        """Crear vector de estado para la red neuronal."""
        return np.array([
            game_state["health_ratio"],
            game_state["ammo_ratio"],
            1.0 if game_state["enemy_present"] else 0.0,
            1.0 if game_state["in_safe_zone"] else 0.0,
            client_state.get("fps", 15) / 30.0,
            client_state.get("ping", 100) / 500.0,
            # Features adicionales
            np.sin(time.time() / 60),  # Tiempo cíclico
            np.cos(time.time() / 60),
        ], dtype=np.float32)
    
    def _apply_heuristics(self, action: str, x: float, y: float, duration: int, game_state: dict) -> Tuple[str, float, float, int]:
        """Aplicar heurísticas de supervivencia."""
        health = game_state["health_ratio"]
        ammo = game_state["ammo_ratio"]
        enemy = game_state["enemy_present"]
        
        # Prioridad 1: Supervivencia
        if health < 0.25 and action not in ["HEAL", "MOVE_BACKWARD"]:
            return "HEAL", 200, 800, 500
        
        # Prioridad 2: Combate efectivo
        if enemy and health > 0.3:
            if ammo < 0.1:
                return "RELOAD", 1200, 900, 1500
            return "SHOOT", 1200, 800, 100
        
        # Prioridad 3: No gastar munición si no hay enemigo
        if action == "SHOOT" and not enemy:
            return "HOLD", x, y, 100
        
        return action, x, y, duration
    
    def _update_session_memory(self, session_id: str, data: dict):
        """Actualizar memoria de sesión."""
        if session_id not in self.session_memory:
            self.session_memory[session_id] = []
        
        self.session_memory[session_id].append(data)
        
        # Mantener solo últimos 100 frames
        if len(self.session_memory[session_id]) > 100:
            self.session_memory[session_id] = self.session_memory[session_id][-100:]

# ==================== FASTAPI APP ====================

app = FastAPI(
    title="Free Fire AI Server Pro",
    description="IA Avanzada para Free Fire - Oracle Cloud Optimized",
    version="3.0.0"
)

# Middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Servicios globales
ai: Optional[AdvancedFreeFireAI] = None
redis_client: Optional[redis.Redis] = None

@app.on_event("startup")
async def startup():
    """Inicializar servicios."""
    global ai, redis_client
    
    try:
        ai = AdvancedFreeFireAI()
        logger.info("Modelo de IA cargado")
    except Exception as e:
        logger.error(f"Error cargando IA: {e}")
        ai = None
    
    try:
        redis_client = await redis.from_url(config.REDIS_URL)
        await redis_client.ping()
        logger.info("Redis conectado")
    except Exception as e:
        logger.warning(f"Redis no disponible: {e}")
        redis_client = None

@app.on_event("shutdown")
async def shutdown():
    """Cerrar servicios."""
    if redis_client:
        await redis_client.close()

# ==================== ENDPOINTS ====================

@app.get("/")
async def root():
    """Health check."""
    return {
        "status": "healthy",
        "service": "Free Fire AI Server Pro",
        "version": "3.0.0",
        "ai_loaded": ai is not None,
        "redis_connected": redis_client is not None,
        "device": str(ai.model.device) if ai else "N/A"
    }

@app.get("/health")
async def health():
    """Health check simple."""
    return {"status": "ok"}

@app.get("/stats")
async def stats():
    """Estadísticas del servidor."""
    if not ai:
        raise HTTPException(status_code=503, detail="AI not loaded")
    
    return {
        "frames_processed": ai.frame_count,
        "active_sessions": len(ai.session_memory),
        "device": str(ai.model.device)
    }

# ==================== WEBSOCKET ====================

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """WebSocket para comunicación con APK."""
    await websocket.accept()
    
    session_id = f"session_{id(websocket)}_{time.time()}"
    logger.info(f"Nueva sesión: {session_id}")
    
    try:
        while True:
            # Recibir mensaje
            message = await websocket.receive()
            
            if "bytes" in message:
                # Datos binarios comprimidos
                data = decompress_frame(message["bytes"])
            else:
                # JSON
                data = json.loads(message["text"])
            
            if data.get("type") == "frame":
                # Procesar frame
                if not ai:
                    await websocket.send_json({
                        "type": "action",
                        "action": "HOLD",
                        "confidence": 0,
                        "error": "AI not loaded"
                    })
                    continue
                
                # Decodificar imagen
                image = decode_image(data.get("imageBase64", ""))
                
                # Procesar
                result = ai.process_frame(
                    session_id=session_id,
                    image=image,
                    client_state={
                        "fps": data.get("fps", 15),
                        "ping": data.get("ping", 100)
                    }
                )
                
                # Enviar respuesta
                await websocket.send_json(result)
    
    except WebSocketDisconnect:
        logger.info(f"Sesión desconectada: {session_id}")
    except Exception as e:
        logger.error(f"Error en sesión {session_id}: {e}")
    finally:
        # Limpiar memoria
        if ai and session_id in ai.session_memory:
            del ai.session_memory[session_id]

# ==================== UTILIDADES ====================

def decompress_frame(compressed_data: bytes) -> dict:
    """Descomprimir frame gzip."""
    try:
        json_bytes = gzip.decompress(compressed_data)
        return json.loads(json_bytes.decode('utf-8'))
    except Exception as e:
        logger.error(f"Error descomprimiendo: {e}")
        return {}

def decode_image(base64_string: str) -> np.ndarray:
    """Decodificar imagen base64."""
    try:
        image_bytes = base64.b64decode(base64_string)
        image = Image.open(io.BytesIO(image_bytes))
        return np.array(image)
    except Exception as e:
        logger.error(f"Error decodificando imagen: {e}")
        # Retornar imagen vacía
        return np.zeros((180, 320, 3), dtype=np.uint8)

# ==================== MAIN ====================

if __name__ == "__main__":
    import uvicorn
    
    logger.info("Iniciando Free Fire AI Server Pro...")
    
    uvicorn.run(
        "main_advanced:app",
        host="0.0.0.0",
        port=8000,
        workers=1,  # 1 worker por contenedor, escalar con Docker
        loop="uvloop",
        access_log=True
    )
