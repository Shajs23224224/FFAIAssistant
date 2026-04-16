# FFAI Core - Motor de IA para Free Fire
# Arquitectura: Colab + Drive + Flask + Ngrok

from .engine import FFAIEngine
from .drive_manager import DriveManager
from .api import create_app

__version__ = "3.1.0"
__all__ = ["FFAIEngine", "DriveManager", "create_app"]
