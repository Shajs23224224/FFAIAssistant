"""
Drive Manager - Persistencia en Google Drive
"""

import os
import json
from pathlib import Path
from typing import Dict, Optional, Any
from datetime import datetime


class DriveManager:
    """Gestiona persistencia de modelos, estados y logs en Google Drive."""
    
    def __init__(self, base_path: str = "/content/drive/MyDrive/FFAI"):
        self.base_path = Path(base_path)
        self.dirs = {
            "models": self.base_path / "models",
            "checkpoints": self.base_path / "checkpoints",
            "logs": self.base_path / "logs",
            "states": self.base_path / "states",
            "data": self.base_path / "data"
        }
        self._mounted = False
        
    def mount_drive(self) -> bool:
        """Montar Google Drive en Colab."""
        if self._mounted:
            return True
            
        try:
            from google.colab import drive
            drive.mount('/content/drive')
            self._mounted = True
            print("✅ Google Drive montado")
            return True
        except Exception as e:
            print(f"⚠️ Error montando Drive: {e}")
            # Fallback a almacenamiento local
            self.base_path = Path("/content/ffai_local")
            self._mounted = True
            print(f"   Usando almacenamiento local: {self.base_path}")
            return False
    
    def ensure_dirs(self):
        """Crear estructura de directorios."""
        for dir_path in self.dirs.values():
            dir_path.mkdir(parents=True, exist_ok=True)
    
    def save_model(self, model_name: str, model_data: bytes) -> str:
        """Guardar modelo binario."""
        path = self.dirs["models"] / f"{model_name}.pt"
        with open(path, "wb") as f:
            f.write(model_data)
        return str(path)
    
    def load_model(self, model_name: str) -> Optional[bytes]:
        """Cargar modelo binario."""
        path = self.dirs["models"] / f"{model_name}.pt"
        if not path.exists():
            return None
        with open(path, "rb") as f:
            return f.read()
    
    def save_checkpoint(self, checkpoint_data: Dict, name: str = None) -> str:
        """Guardar checkpoint con timestamp."""
        if name is None:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            name = f"checkpoint_{timestamp}"
        
        path = self.dirs["checkpoints"] / f"{name}.pth"
        
        import torch
        torch.save(checkpoint_data, path)
        
        # También guardar como "latest" para fácil acceso
        latest_path = self.dirs["checkpoints"] / "checkpoint_latest.pth"
        torch.save(checkpoint_data, latest_path)
        
        return str(path)
    
    def load_checkpoint(self, name: str = "latest") -> Optional[Dict]:
        """Cargar checkpoint."""
        if name == "latest":
            path = self.dirs["checkpoints"] / "checkpoint_latest.pth"
        else:
            path = self.dirs["checkpoints"] / f"{name}.pth"
        
        if not path.exists():
            return None
            
        import torch
        return torch.load(path, map_location="cpu")
    
    def list_checkpoints(self) -> list:
        """Listar checkpoints disponibles."""
        checkpoints = []
        for file in self.dirs["checkpoints"].glob("*.pth"):
            stat = file.stat()
            checkpoints.append({
                "name": file.stem,
                "path": str(file),
                "size_mb": round(stat.st_size / (1024*1024), 2),
                "modified": datetime.fromtimestamp(stat.st_mtime).isoformat()
            })
        return sorted(checkpoints, key=lambda x: x["modified"], reverse=True)
    
    def save_state(self, session_id: str, state: Dict):
        """Guardar estado de sesión."""
        path = self.dirs["states"] / f"{session_id}.json"
        with open(path, "w") as f:
            json.dump(state, f, indent=2)
    
    def load_state(self, session_id: str) -> Optional[Dict]:
        """Cargar estado de sesión."""
        path = self.dirs["states"] / f"{session_id}.json"
        if not path.exists():
            return None
        with open(path, "r") as f:
            return json.load(f)
    
    def log(self, message: str, session_id: str = "main"):
        """Escribir al archivo de log."""
        timestamp = datetime.now().isoformat()
        log_line = f"[{timestamp}] {message}\n"
        
        log_file = self.dirs["logs"] / f"{session_id}_{datetime.now().strftime('%Y%m%d')}.log"
        with open(log_file, "a") as f:
            f.write(log_line)
        
        # También log general
        general_log = self.dirs["logs"] / "general.log"
        with open(general_log, "a") as f:
            f.write(log_line)
    
    def save_metrics(self, session_id: str, metrics: Dict):
        """Guardar métricas de rendimiento."""
        path = self.dirs["data"] / f"metrics_{session_id}.json"
        
        # Cargar existentes o crear nuevo
        if path.exists():
            with open(path, "r") as f:
                data = json.load(f)
        else:
            data = {"metrics": []}
        
        data["metrics"].append({
            "timestamp": datetime.now().isoformat(),
            **metrics
        })
        
        with open(path, "w") as f:
            json.dump(data, f, indent=2)
    
    def get_storage_stats(self) -> Dict:
        """Obtener estadísticas de uso de almacenamiento."""
        stats = {}
        for name, dir_path in self.dirs.items():
            total_size = 0
            file_count = 0
            for file in dir_path.rglob("*"):
                if file.is_file():
                    total_size += file.stat().st_size
                    file_count += 1
            stats[name] = {
                "files": file_count,
                "size_mb": round(total_size / (1024*1024), 2)
            }
        return stats
    
    def cleanup_old_checkpoints(self, keep_last: int = 5):
        """Eliminar checkpoints antiguos, manteniendo los últimos N."""
        checkpoints = self.list_checkpoints()
        if len(checkpoints) > keep_last:
            for ckpt in checkpoints[keep_last:]:
                if "latest" not in ckpt["name"]:
                    Path(ckpt["path"]).unlink(missing_ok=True)
                    print(f"🗑️ Eliminado: {ckpt['name']}")
