"""Asegura entorno de prueba antes de importar la app ASGI."""

from __future__ import annotations

import os

# Debe ejecutarse antes de importar tactical_ai.api.main (crea la app al cargar el módulo).
os.environ["ENVIRONMENT"] = "test"

from tactical_ai.settings import get_settings

get_settings.cache_clear()
