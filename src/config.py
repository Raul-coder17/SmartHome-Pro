import os
import json

# Determina la ruta del archivo dispositivos.json de forma relativa a este archivo
DIR_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH_DISPOSITIVOS = os.path.join(DIR_BASE, "dispositivos.json")

def cargar_dispositivos():
    """Carga los dispositivos desde el archivo JSON. Si no existe, retorna una lista vacía."""
    if not os.path.exists(PATH_DISPOSITIVOS):
        return []
    try:
        with open(PATH_DISPOSITIVOS, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"Error al leer {PATH_DISPOSITIVOS}: {e}")
        return []

def guardar_dispositivos(dispositivos):
    """Guarda la lista de dispositivos en el archivo JSON."""
    try:
        with open(PATH_DISPOSITIVOS, "w", encoding="utf-8") as f:
            json.dump(dispositivos, f, indent=2, ensure_ascii=False)
        print(f"[OK] Archivo '{PATH_DISPOSITIVOS}' actualizado correctamente.")
    except Exception as e:
        print(f"[ERROR] Error al guardar los dispositivos: {e}")
