#!/usr/bin/env python3
"""
Servidor de IA para Free Fire Assistant.
Recibe frames vía WebSocket, procesa con IA y devuelve acciones.
"""

import asyncio
import base64
import io
import json
import logging
from datetime import datetime
from typing import Optional

import numpy as np
from PIL import Image
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

# Configurar logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Crear app FastAPI
app = FastAPI(
    title="Free Fire AI Server",
    description="Servidor de IA para procesamiento de gameplay",
    version="1.0.0"
)

# CORS para permitir conexiones desde cualquier origen
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ConnectionManager:
    """Gestiona conexiones WebSocket activas."""
    
    def __init__(self):
        self.active_connections: list[WebSocket] = []
    
    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)
        logger.info(f"Cliente conectado. Total: {len(self.active_connections)}")
    
    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
        logger.info(f"Cliente desconectado. Total: {len(self.active_connections)}")
    
    async def send_action(self, websocket: WebSocket, action: dict):
        try:
            await websocket.send_json(action)
        except Exception as e:
            logger.error(f"Error enviando acción: {e}")

manager = ConnectionManager()


class FreeFireAI:
    """IA simple para Free Fire."""
    
    def __init__(self):
        self.frame_count = 0
        self.last_action_time = datetime.now()
        logger.info("IA inicializada")
    
    def process_frame(self, image: np.ndarray, health: float, ammo: float) -> dict:
        """
        Procesa un frame y decide la acción.
        
        Args:
            image: Frame como array numpy (RGB)
            health: Ratio de vida (0-1)
            ammo: Ratio de munición (0-1)
        
        Returns:
            Dict con la acción a ejecutar
        """
        self.frame_count += 1
        
        # Lógica básica de IA (placeholder para modelo real)
        action = self._basic_ai_logic(health, ammo)
        
        if self.frame_count % 100 == 0:
            logger.info(f"Frames procesados: {self.frame_count}")
        
        return action
    
    def _basic_ai_logic(self, health: float, ammo: float) -> dict:
        """Lógica básica de supervivencia."""
        
        # Prioridad 1: Curar si vida baja
        if health < 0.3:
            return {
                "type": "action",
                "action": "HEAL",
                "confidence": 0.9,
                "coordinates": {"x": 200, "y": 800},
                "duration": 500
            }
        
        # Prioridad 2: Recargar si munición baja
        if ammo < 0.2:
            return {
                "type": "action",
                "action": "RELOAD",
                "confidence": 0.85,
                "coordinates": {"x": 1200, "y": 900},
                "duration": 1000
            }
        
        # Prioridad 3: Disparar (simulado - en versión real detectar enemigos)
        # Por ahora dispara aleatoriamente cada ~5 segundos
        import random
        if random.random() < 0.02:  # 2% de probabilidad por frame
            return {
                "type": "action",
                "action": "SHOOT",
                "confidence": 0.7,
                "coordinates": {"x": 1200, "y": 800},
                "duration": 100
            }
        
        # Default: mantener posición
        return {
            "type": "action",
            "action": "HOLD",
            "confidence": 0.5,
            "coordinates": {"x": 0, "y": 0},
            "duration": 100
        }

# Instancia global de la IA
ai = FreeFireAI()


@app.get("/")
async def root():
    """Endpoint de health check."""
    return {
        "status": "ok",
        "service": "Free Fire AI Server",
        "version": "1.0.0",
        "active_connections": len(manager.active_connections)
    }


@app.get("/stats")
async def stats():
    """Estadísticas del servidor."""
    return {
        "frames_processed": ai.frame_count,
        "active_connections": len(manager.active_connections)
    }


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """
    Endpoint WebSocket para comunicación con el APK.
    Recibe frames y envía acciones.
    """
    await manager.connect(websocket)
    
    try:
        while True:
            # Recibir mensaje del cliente
            message = await websocket.receive_text()
            data = json.loads(message)
            
            if data.get("type") == "frame":
                # Procesar frame
                action = await process_frame_message(data)
                
                # Enviar acción al cliente
                await manager.send_action(websocket, action)
    
    except WebSocketDisconnect:
        logger.info("WebSocket desconectado")
    except Exception as e:
        logger.error(f"Error en WebSocket: {e}")
    finally:
        manager.disconnect(websocket)


async def process_frame_message(data: dict) -> dict:
    """Procesa un mensaje de frame recibido."""
    try:
        # Extraer datos
        image_base64 = data.get("imageBase64", "")
        health = data.get("health", 1.0)
        ammo = data.get("ammo", 1.0)
        
        # Decodificar imagen
        if image_base64:
            image_bytes = base64.b64decode(image_base64)
            image = Image.open(io.BytesIO(image_bytes))
            image_array = np.array(image)
        else:
            # Frame vacío si no hay imagen
            image_array = np.zeros((180, 320, 3), dtype=np.uint8)
        
        # Procesar con IA
        action = ai.process_frame(image_array, health, ammo)
        
        return action
    
    except Exception as e:
        logger.error(f"Error procesando frame: {e}")
        return {
            "type": "action",
            "action": "HOLD",
            "confidence": 0.0,
            "error": str(e)
        }


if __name__ == "__main__":
    import uvicorn
    
    logger.info("Iniciando servidor Free Fire AI...")
    logger.info("WebSocket endpoint: ws://localhost:8080/ws")
    
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8080,
        reload=False,
        log_level="info"
    )
