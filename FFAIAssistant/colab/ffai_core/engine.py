"""
Motor de IA - Procesamiento de gameplay y decisiones tácticas
"""

import torch
import torch.nn as nn
import numpy as np
import cv2
from PIL import Image
import io
import base64
import time
from typing import Dict, Tuple, Optional, List


class TacticalNN(nn.Module):
    """Red neuronal para decisiones tácticas."""
    
    def __init__(self, input_size: int = 128, hidden: int = 256, actions: int = 10):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(input_size, hidden),
            nn.ReLU(),
            nn.Dropout(0.2),
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            nn.Dropout(0.2),
            nn.Linear(hidden, hidden // 2),
            nn.ReLU(),
            nn.Linear(hidden // 2, actions)
        )
        
    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        logits = self.net(x)
        return torch.softmax(logits, dim=-1), logits


class FFAIEngine:
    """Motor principal de IA para Free Fire."""
    
    ACTIONS = {
        0: ("HOLD", 960, 700, 100),
        1: ("SHOOT", 1150, 750, 100),
        2: ("AIM", 1150, 600, 200),
        3: ("FORWARD", 960, 500, 300),
        4: ("BACK", 960, 900, 300),
        5: ("LEFT", 700, 700, 300),
        6: ("RIGHT", 1220, 700, 300),
        7: ("RELOAD", 1200, 900, 1500),
        8: ("HEAL", 150, 800, 500),
        9: ("JUMP", 960, 700, 100),
    }
    
    def __init__(self, device: str = "cpu"):
        self.device = torch.device(device)
        self.model = TacticalNN().to(self.device)
        self.model.eval()
        
        self.frame_count = 0
        self.session_start = time.time()
        self.session_memory: List[Dict] = []
        self.last_health = 1.0
        self.last_ammo = 1.0
        
        print(f"🧠 FFAI Engine iniciado en {device}")
    
    def load_model(self, model_path: str) -> bool:
        """Cargar modelo desde archivo."""
        try:
            checkpoint = torch.load(model_path, map_location=self.device)
            self.model.load_state_dict(checkpoint['model_state_dict'])
            print(f"✅ Modelo cargado: {model_path}")
            return True
        except Exception as e:
            print(f"⚠️ No se pudo cargar modelo: {e}")
            return False
    
    def save_checkpoint(self, path: str, metadata: Dict = None):
        """Guardar checkpoint del modelo."""
        checkpoint = {
            'model_state_dict': self.model.state_dict(),
            'frame_count': self.frame_count,
            'session_time': time.time() - self.session_start,
            'timestamp': time.time(),
            'metadata': metadata or {}
        }
        torch.save(checkpoint, path)
        return checkpoint
    
    def decode_image(self, base64_string: str) -> np.ndarray:
        """Decodificar imagen base64 a numpy array."""
        try:
            image_bytes = base64.b64decode(base64_string)
            image = Image.open(io.BytesIO(image_bytes))
            return np.array(image)
        except Exception as e:
            print(f"Error decodificando: {e}")
            return np.zeros((135, 240, 3), dtype=np.uint8)
    
    def analyze_frame(self, image: np.ndarray) -> Dict:
        """Analizar frame y extraer estado del juego."""
        h, w = image.shape[:2]
        
        # Zonas de interés (ajustadas para Free Fire mobile)
        health_zone = image[int(h*0.88):int(h*0.96), int(w*0.02):int(w*0.22)]
        ammo_zone = image[int(h*0.88):int(h*0.96), int(w*0.78):int(w*0.98)]
        enemy_zone = image[int(h*0.15):int(h*0.55), int(w*0.35):int(w*0.65)]
        
        # Análisis de vida (color verde)
        hsv_health = cv2.cvtColor(health_zone, cv2.COLOR_RGB2HSV)
        green_mask = cv2.inRange(hsv_health, np.array([35, 40, 40]), np.array([85, 255, 255]))
        health_ratio = min(np.sum(green_mask > 0) / green_mask.size * 3, 1.0)
        
        # Análisis de munición (texto blanco)
        gray_ammo = cv2.cvtColor(ammo_zone, cv2.COLOR_RGB2GRAY)
        ammo_ratio = min(np.sum(gray_ammo > 200) / gray_ammo.size * 3, 1.0)
        
        # Detección de enemigos (colores rojos)
        hsv_enemy = cv2.cvtColor(enemy_zone, cv2.COLOR_RGB2HSV)
        red_mask1 = cv2.inRange(hsv_enemy, np.array([0, 50, 50]), np.array([10, 255, 255]))
        red_mask2 = cv2.inRange(hsv_enemy, np.array([160, 50, 50]), np.array([180, 255, 255]))
        red_mask = cv2.bitwise_or(red_mask1, red_mask2)
        enemy_present = np.sum(red_mask > 0) / red_mask.size > 0.08
        
        return {
            "health_ratio": float(health_ratio),
            "ammo_ratio": float(ammo_ratio),
            "enemy_present": bool(enemy_present),
            "in_safe_zone": True,
            "resolution": f"{w}x{h}"
        }
    
    def create_state_vector(self, game_state: Dict, client_state: Dict) -> np.ndarray:
        """Crear vector de estado para la red neuronal."""
        import math
        t = time.time()
        
        return np.array([
            game_state["health_ratio"],
            game_state["ammo_ratio"],
            1.0 if game_state["enemy_present"] else 0.0,
            1.0 if game_state["in_safe_zone"] else 0.0,
            client_state.get("fps", 15) / 30.0,
            min(client_state.get("ping", 100) / 500.0, 1.0),
            math.sin(t / 60),
            math.cos(t / 60),
        ], dtype=np.float32)
    
    def apply_heuristics(self, action_name: str, x: int, y: int, duration: int, 
                         game_state: Dict) -> Tuple[str, int, int, int]:
        """Aplicar reglas de supervivencia."""
        health = game_state["health_ratio"]
        ammo = game_state["ammo_ratio"]
        enemy = game_state["enemy_present"]
        
        # Prioridad 1: Supervivencia
        if health < 0.25 and action_name not in ["HEAL", "BACK"]:
            return "HEAL", 150, 800, 500
        
        # Prioridad 2: Combate efectivo
        if enemy and health > 0.3:
            if ammo < 0.1:
                return "RELOAD", 1200, 900, 1500
            return "SHOOT", 1150, 750, 100
        
        # Prioridad 3: No gastar munición si no hay enemigo
        if action_name == "SHOOT" and not enemy:
            return "HOLD", x, y, 100
        
        return action_name, x, y, duration
    
    def process(self, image_b64: str, client_state: Dict = None) -> Dict:
        """Procesar frame y retornar acción."""
        client_state = client_state or {}
        self.frame_count += 1
        
        # 1. Decodificar imagen
        image = self.decode_image(image_b64)
        
        # 2. Analizar estado del juego
        game_state = self.analyze_frame(image)
        
        # 3. Crear vector de estado
        state_vector = self.create_state_vector(game_state, client_state)
        
        # 4. Inferencia IA
        with torch.no_grad():
            state_tensor = torch.FloatTensor(state_vector).unsqueeze(0).to(self.device)
            probs, _ = self.model(state_tensor)
            action_idx = torch.multinomial(probs[0], 1).item()
            confidence = probs[0][action_idx].item()
        
        # 5. Mapear a acción
        action_name, x, y, duration = self.ACTIONS.get(
            action_idx, ("HOLD", 960, 700, 100)
        )
        
        # 6. Ajustar por heurísticas
        action_name, x, y, duration = self.apply_heuristics(
            action_name, x, y, duration, game_state
        )
        
        # 7. Guardar en memoria
        self._update_memory({
            "frame": self.frame_count,
            "timestamp": time.time(),
            "game_state": game_state,
            "action": action_name,
            "confidence": confidence
        })
        
        return {
            "type": "action",
            "action": action_name,
            "confidence": round(confidence, 3),
            "coordinates": {"x": x, "y": y},
            "duration": duration,
            "game_state": game_state
        }
    
    def _update_memory(self, data: Dict):
        """Actualizar memoria de sesión."""
        self.session_memory.append(data)
        if len(self.session_memory) > 100:
            self.session_memory = self.session_memory[-100:]
    
    def get_stats(self) -> Dict:
        """Obtener estadísticas de la sesión."""
        return {
            "frame_count": self.frame_count,
            "session_time": time.time() - self.session_start,
            "device": str(self.device),
            "memory_size": len(self.session_memory)
        }
