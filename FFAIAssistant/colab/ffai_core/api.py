"""
Flask API - Interfaz REST y WebSocket
"""

from flask import Flask, request, jsonify
from flask_socketio import SocketIO, emit
from flask_cors import CORS
import json
import time
from datetime import datetime
from typing import Dict, Optional


class FFAPI:
    """API Flask para el motor de IA."""
    
    def __init__(self, engine, drive_manager):
        self.engine = engine
        self.drive = drive_manager
        self.app = Flask(__name__)
        self.socketio = SocketIO(self.app, cors_allowed_origins="*", async_mode="threading")
        CORS(self.app)
        
        self.request_count = 0
        self.active_sessions = {}
        
        self._setup_routes()
        self._setup_websocket()
    
    def _setup_routes(self):
        """Configurar rutas REST."""
        
        @self.app.route("/")
        def root():
            """Health check."""
            return jsonify({
                "status": "🟢 Online",
                "service": "Free Fire AI API",
                "version": "3.1.0",
                "timestamp": datetime.now().isoformat(),
                "stats": self.engine.get_stats()
            })
        
        @self.app.route("/health")
        def health():
            """Health check simple."""
            return jsonify({"status": "ok"})
        
        @self.app.route("/api/v1/process", methods=["POST"])
        def process():
            """Procesar un frame individual."""
            try:
                data = request.get_json()
                if not data or "imageBase64" not in data:
                    return jsonify({"error": "Missing imageBase64"}), 400
                
                result = self.engine.process(
                    data["imageBase64"],
                    data.get("clientState", {})
                )
                
                self.request_count += 1
                return jsonify(result)
                
            except Exception as e:
                self.drive.log(f"Error en /api/v1/process: {e}")
                return jsonify({"error": str(e)}), 500
        
        @self.app.route("/api/v1/state", methods=["GET"])
        def get_state():
            """Obtener estado actual del motor."""
            return jsonify({
                "engine_stats": self.engine.get_stats(),
                "active_sessions": len(self.active_sessions),
                "total_requests": self.request_count
            })
        
        @self.app.route("/api/v1/save", methods=["POST"])
        def save_checkpoint():
            """Guardar checkpoint manual."""
            try:
                data = request.get_json() or {}
                checkpoint = self.engine.save_checkpoint(
                    path=str(self.drive.dirs["checkpoints"] / "manual_save.pth"),
                    metadata=data.get("metadata", {})
                )
                self.drive.log("Checkpoint manual guardado")
                return jsonify({"status": "saved", "path": "manual_save.pth"})
            except Exception as e:
                return jsonify({"error": str(e)}), 500
        
        @self.app.route("/api/v1/checkpoints", methods=["GET"])
        def list_checkpoints():
            """Listar checkpoints disponibles."""
            return jsonify({
                "checkpoints": self.drive.list_checkpoints()
            })
        
        @self.app.route("/api/v1/storage", methods=["GET"])
        def storage_stats():
            """Estadísticas de almacenamiento."""
            return jsonify(self.drive.get_storage_stats())
    
    def _setup_websocket(self):
        """Configurar WebSocket events."""
        
        @self.socketio.on("connect")
        def handle_connect():
            """Nuevo cliente conectado."""
            session_id = request.sid
            self.active_sessions[session_id] = {
                "connected_at": time.time(),
                "frames_processed": 0
            }
            self.drive.log(f"Cliente conectado: {session_id}")
            emit("connected", {"session_id": session_id})
        
        @self.socketio.on("disconnect")
        def handle_disconnect():
            """Cliente desconectado."""
            session_id = request.sid
            if session_id in self.active_sessions:
                session_data = self.active_sessions.pop(session_id)
                duration = time.time() - session_data["connected_at"]
                self.drive.log(f"Cliente desconectado: {session_id} (duración: {duration:.1f}s)")
        
        @self.socketio.on("frame")
        def handle_frame(data):
            """Recibir frame del cliente."""
            try:
                session_id = request.sid
                
                if not isinstance(data, dict) or "imageBase64" not in data:
                    emit("error", {"message": "Invalid frame data"})
                    return
                
                # Procesar frame
                result = self.engine.process(
                    data["imageBase64"],
                    data.get("clientState", {})
                )
                
                # Actualizar stats
                if session_id in self.active_sessions:
                    self.active_sessions[session_id]["frames_processed"] += 1
                
                # Guardar métricas
                self.drive.save_metrics(session_id, {
                    "action": result["action"],
                    "confidence": result["confidence"],
                    "health": result["game_state"]["health_ratio"]
                })
                
                # Enviar respuesta
                emit("action", result)
                
            except Exception as e:
                self.drive.log(f"Error procesando frame: {e}")
                emit("error", {"message": str(e)})
        
        @self.socketio.on("ping")
        def handle_ping():
            """Keepalive ping."""
            emit("pong", {"timestamp": time.time()})
    
    def run(self, host="0.0.0.0", port=5000, debug=False):
        """Iniciar servidor."""
        print(f"🚀 Iniciando API en http://{host}:{port}")
        self.socketio.run(self.app, host=host, port=port, debug=debug, allow_unsafe_werkzeug=True)


# Factory function
def create_app(engine, drive_manager):
    """Crear instancia de la aplicación."""
    api = FFAPI(engine, drive_manager)
    return api.app, api.socketio, api
