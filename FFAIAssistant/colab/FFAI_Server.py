# Free Fire AI Server - Google Colab Edition
# Guardar como .ipynb en Colab o ejecutar como script

# === CELDA 1: Instalar ===
"""
!pip install -q fastapi uvicorn websockets pyngrok pillow numpy torch torchvision opencv-python-headless
print("✅ Listo")
"""

# === CELDA 2: Imports ===
import torch
import torch.nn as nn
import numpy as np
from PIL import Image
import cv2
import json
import base64
import io
from fastapi import FastAPI, WebSocket
from fastapi.middleware.cors import CORSMiddleware

# GPU Check
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"Device: {device}")

# === CELDA 3: IA ===
class TacticalNN(nn.Module):
    def __init__(self, input_size=128, hidden=256, actions=10):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(input_size, hidden), nn.ReLU(), nn.Dropout(0.2),
            nn.Linear(hidden, hidden), nn.ReLU(), nn.Dropout(0.2),
            nn.Linear(hidden, hidden//2), nn.ReLU(),
            nn.Linear(hidden//2, actions)
        )
    
    def forward(self, x):
        return torch.softmax(self.net(x), dim=-1)

class FreeFireAI:
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
    
    def __init__(self):
        self.model = TacticalNN().to(device)
        self.model.eval()
        self.frames = 0
    
    def analyze(self, img):
        h, w = img.shape[:2]
        health = img[int(h*0.88):int(h*0.96), int(w*0.02):int(w*0.22)]
        ammo = img[int(h*0.88):int(h*0.96), int(w*0.78):int(w*0.98)]
        enemy = img[int(h*0.15):int(h*0.55), int(w*0.35):int(w*0.65)]
        
        health_ratio = min(np.sum(cv2.inRange(cv2.cvtColor(health, cv2.COLOR_RGB2HSV), 
            np.array([35,40,40]), np.array([85,255,255])) > 0) / health.size * 3, 1.0)
        
        ammo_ratio = min(np.sum(cv2.cvtColor(ammo, cv2.COLOR_RGB2GRAY) > 200) / ammo.size * 3, 1.0)
        
        hsv = cv2.cvtColor(enemy, cv2.COLOR_RGB2HSV)
        enemy_present = (np.sum(cv2.inRange(hsv, np.array([0,50,50]), np.array([10,255,255])) > 0) / hsv.size > 0.08)
        
        return health_ratio, ammo_ratio, enemy_present
    
    def decide(self, img):
        self.frames += 1
        health, ammo, enemy = self.analyze(img)
        
        # Heurísticas
        if health < 0.25: return "HEAL", 150, 800, 500
        if enemy and ammo < 0.1: return "RELOAD", 1200, 900, 1500
        if enemy: return "SHOOT", 1150, 750, 100
        
        # IA
        state = torch.FloatTensor([health, ammo, float(enemy), 1.0, 0.5, 0.2, 0.0, 0.0]).to(device)
        probs = self.model(state.unsqueeze(0))[0]
        action_idx = torch.multinomial(probs, 1).item()
        
        name, x, y, dur = self.ACTIONS.get(action_idx, ("HOLD", 960, 700, 100))
        return name, x, y, dur

ai = FreeFireAI()
print("✅ IA lista")

# === CELDA 4: Servidor ===
app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

def decode(b64):
    try:
        return np.array(Image.open(io.BytesIO(base64.b64decode(b64))))
    except:
        return np.zeros((180, 320, 3), dtype=np.uint8)

@app.get("/")
def root():
    return {"status": "🟢 Online", "frames": ai.frames, "device": str(device)}

@app.websocket("/ws")
async def ws(websocket: WebSocket):
    await websocket.accept()
    while True:
        msg = json.loads(await websocket.receive_text())
        if msg.get("type") == "frame":
            img = decode(msg.get("imageBase64", ""))
            name, x, y, dur = ai.decide(img)
            await websocket.send_json({
                "type": "action", "action": name,
                "coordinates": {"x": x, "y": y}, "duration": dur
            })

print("✅ Servidor listo - Ejecuta celda de ngrok y uvicorn")
