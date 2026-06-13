import os
import json
import time
import uuid
import threading
import concurrent.futures
from datetime import datetime
from flask import Flask, jsonify, request, render_template
from src.device import ControladorSurplife
from src.config import cargar_dispositivos as _cargar_dispositivos, guardar_dispositivos as _guardar_dispositivos
from src.discovery import escanear_red

app = Flask(__name__)

# Rutas de base de datos
DIR_BASE = os.path.dirname(os.path.abspath(__file__))
PATH_COLORES = os.path.join(DIR_BASE, "colores.json")
PATH_PROGRAMACIONES = os.path.join(DIR_BASE, "programaciones.json")

# Caché en memoria global para el estado de los dispositivos
CACHE_ESTADOS = {}
# Bloqueo para operaciones seguras en hilos sobre la caché
cache_lock = threading.Lock()
# Bloqueo para lectura/escritura concurrente de archivos JSON
file_io_lock = threading.Lock()

# Mapeo de día de semana en español
DIAS_MAP = ["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"]

def cargar_dispositivos():
    with file_io_lock:
        return _cargar_dispositivos()

def guardar_dispositivos(dispositivos):
    with file_io_lock:
        _guardar_dispositivos(dispositivos)

# Carga de colores favoritos
def cargar_colores():
    with file_io_lock:
        if not os.path.exists(PATH_COLORES):
            return []
        try:
            with open(PATH_COLORES, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []

def guardar_colores(colores):
    with file_io_lock:
        try:
            with open(PATH_COLORES, "w", encoding="utf-8") as f:
                json.dump(colores, f, indent=2, ensure_ascii=False)
        except Exception as e:
            print(f"Error al guardar colores: {e}")

# Carga y guardado de programaciones
def cargar_programaciones():
    with file_io_lock:
        if not os.path.exists(PATH_PROGRAMACIONES):
            return []
        try:
            with open(PATH_PROGRAMACIONES, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []

def guardar_programaciones(progs):
    with file_io_lock:
        try:
            with open(PATH_PROGRAMACIONES, "w", encoding="utf-8") as f:
                json.dump(progs, f, indent=2, ensure_ascii=False)
        except Exception as e:
            print(f"Error al guardar programaciones: {e}")

def verificar_estado_dispositivo(dev):
    """Verifica el estado de red de un dispositivo en paralelo."""
    ctrl = ControladorSurplife(dev["ip"], silent=True)
    is_online = ctrl.dispositivo is not None
    estado_detallado = {
        "nombre": dev["nombre"],
        "ip": dev["ip"],
        "tipo": dev["tipo"],
        "online": is_online,
        "encendido": False
    }
    if is_online:
        try:
            estado_detallado["encendido"] = ctrl.dispositivo.is_on
        except Exception:
            pass
    return estado_detallado

# --- Hilo 1: Monitoreo en segundo plano (Live Polling Cache) ---
def worker_monitoreo():
    print("[Monitoreo] Hilo de monitoreo iniciado.")
    while True:
        try:
            dispositivos = cargar_dispositivos()
            if dispositivos:
                with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
                    resultados = list(executor.map(verificar_estado_dispositivo, dispositivos))
                
                with cache_lock:
                    for res in resultados:
                        CACHE_ESTADOS[res["ip"]] = res
            time.sleep(5)
        except Exception as e:
            print(f"[Monitoreo] Error en hilo de monitoreo: {e}")
            time.sleep(5)

# --- Hilo 2: Programador de tareas local (Precisión de 2 segundos) ---
def worker_programador():
    print("[Programador] Hilo de programación de tareas iniciado (Precisión: 2s).")
    ultimo_minuto_ejecutado = None
    
    while True:
        try:
            ahora = datetime.now()
            minuto_actual = ahora.strftime("%H:%M")
            dia_actual = DIAS_MAP[ahora.weekday()]
            
            # Solo verificar una vez al cambiar el minuto
            if minuto_actual != ultimo_minuto_ejecutado:
                programaciones = cargar_programaciones()
                for prog in programaciones:
                    if prog.get("activo", True):
                        if prog.get("hora") == minuto_actual:
                            dias = prog.get("dias", [])
                            if not dias or "Diario" in dias or dia_actual in dias:
                                # Ejecutar acción
                                ip = prog["ip"]
                                accion = prog["accion"]
                                print(f"[Programador] Ejecutando acción '{accion}' para el dispositivo {ip}...")
                                
                                controlador = ControladorSurplife(ip, silent=True)
                                if controlador.dispositivo:
                                    if accion == "encender":
                                        controlador.encender()
                                    elif accion == "apagar":
                                        controlador.apagar()
                                    
                                    # Actualizar la caché inmediatamente para el refresco visual
                                    with cache_lock:
                                        if ip in CACHE_ESTADOS:
                                            CACHE_ESTADOS[ip]["encendido"] = (accion == "encender")
                                            CACHE_ESTADOS[ip]["online"] = True
                ultimo_minuto_ejecutado = minuto_actual
            time.sleep(2)
        except Exception as e:
            print(f"[Programador] Error en hilo de programación: {e}")
            time.sleep(2)

# Arrancar hilos demonio
t_monitoreo = threading.Thread(target=worker_monitoreo, daemon=True)
t_monitoreo.start()

t_programador = threading.Thread(target=worker_programador, daemon=True)
t_programador.start()

# --- Rutas de Flask ---

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/api/dispositivos", methods=["GET"])
def obtener_dispositivos():
    # Retornar los estados desde la caché para responder al instante (<1ms)
    dispositivos = cargar_dispositivos()
    respuesta = []
    
    with cache_lock:
        for dev in dispositivos:
            ip = dev["ip"]
            # Si el dispositivo no está en caché todavía, hacer una consulta rápida o devolver offline provisional
            if ip in CACHE_ESTADOS:
                respuesta.append(CACHE_ESTADOS[ip])
            else:
                # Datos provisionales hasta que el hilo de monitoreo lo procese
                respuesta.append({
                    "nombre": dev["nombre"],
                    "ip": ip,
                    "tipo": dev["tipo"],
                    "online": False,
                    "encendido": False
                })
    return jsonify(respuesta)

@app.route("/api/dispositivos/control", methods=["POST"])
def control_dispositivo():
    data = request.json or {}
    ip = data.get("ip")
    accion = data.get("accion")
    params = data.get("params", {})
    
    if not ip or not accion:
        return jsonify({"success": False, "error": "Faltan parámetros requeridos"}), 400
        
    controlador = ControladorSurplife(ip, silent=True)
    if not controlador.dispositivo:
        return jsonify({"success": False, "error": f"No se pudo conectar al dispositivo en {ip}"}), 503
        
    try:
        if accion == "encender":
            controlador.encender()
        elif accion == "apagar":
            controlador.apagar()
        elif accion == "color":
            r = int(params.get("r", 255))
            g = int(params.get("g", 255))
            b = int(params.get("b", 255))
            controlador.cambiar_color_rgb(r, g, b)
        elif accion == "blanco":
            calidez = int(params.get("calidez", 50))
            intensidad = int(params.get("intensidad", 100))
            controlador.cambiar_blanco(calidez, intensidad)
        else:
            return jsonify({"success": False, "error": "Acción no válida"}), 400
            
        # Actualizar la caché local al instante para respuesta visual inmediata
        with cache_lock:
            if ip in CACHE_ESTADOS:
                CACHE_ESTADOS[ip]["online"] = True
                if accion in ["encender", "apagar"]:
                    CACHE_ESTADOS[ip]["encendido"] = (accion == "encender")
                    
        return jsonify({"success": True})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/dispositivos/escanear", methods=["GET"])
def escanear_dispositivos():
    detectados = escanear_red()
    dispositivos_registrados = cargar_dispositivos()
    ips_registradas = {d["ip"] for d in dispositivos_registrados}
    
    nuevos = [d for d in detectados if d.get("ipaddr") not in ips_registradas]
    respuesta = []
    for d in nuevos:
        respuesta.append({
            "ip": d.get("ipaddr"),
            "id": d.get("id"),
            "modelo": d.get("model", "Desconocido")
        })
    return jsonify(respuesta)

@app.route("/api/dispositivos/agregar", methods=["POST"])
def agregar_dispositivo():
    data = request.json or {}
    nombre = data.get("nombre")
    ip = data.get("ip")
    tipo = data.get("tipo")
    
    if not nombre or not ip or not tipo:
        return jsonify({"success": False, "error": "Faltan parámetros requeridos"}), 400
        
    dispositivos = cargar_dispositivos()
    if any(d["ip"] == ip for d in dispositivos):
        return jsonify({"success": False, "error": "El dispositivo con esa IP ya existe"}), 400
        
    dispositivos.append({
        "nombre": nombre,
        "ip": ip,
        "tipo": tipo
    })
    guardar_dispositivos(dispositivos)
    
    # Inyectar en caché provisionalmente para no esperar los 5 segundos del hilo
    with cache_lock:
        CACHE_ESTADOS[ip] = {
            "nombre": nombre,
            "ip": ip,
            "tipo": tipo,
            "online": False,
            "encendido": False
        }
        
    return jsonify({"success": True})

@app.route("/api/dispositivos/eliminar", methods=["POST"])
def eliminar_dispositivo():
    data = request.json or {}
    ip = data.get("ip")
    if not ip:
        return jsonify({"success": False, "error": "IP del dispositivo requerida"}), 400
        
    dispositivos = cargar_dispositivos()
    filtrados = [d for d in dispositivos if d["ip"] != ip]
    
    if len(dispositivos) == len(filtrados):
        return jsonify({"success": False, "error": "Dispositivo no encontrado"}), 404
        
    guardar_dispositivos(filtrados)
    
    # Eliminar de la caché
    with cache_lock:
        if ip in CACHE_ESTADOS:
            del CACHE_ESTADOS[ip]
            
    # Eliminar programaciones asociadas a ese dispositivo
    programaciones = cargar_programaciones()
    prog_filtradas = [p for p in programaciones if p["ip"] != ip]
    guardar_programaciones(prog_filtradas)
            
    return jsonify({"success": True})

@app.route("/api/dispositivos/control-global", methods=["POST"])
def control_global():
    data = request.json or {}
    accion = data.get("accion")
    if accion not in ["encender", "apagar"]:
        return jsonify({"success": False, "error": "Acción no admitida"}), 400
        
    dispositivos = cargar_dispositivos()
    
    def enviar_comando(dev):
        try:
            ctrl = ControladorSurplife(dev["ip"], silent=True)
            if ctrl.dispositivo:
                if accion == "encender":
                    ctrl.encender()
                elif accion == "apagar":
                    ctrl.apagar()
                
                # Actualizar caché al instante
                with cache_lock:
                    if dev["ip"] in CACHE_ESTADOS:
                        CACHE_ESTADOS[dev["ip"]]["encendido"] = (accion == "encender")
                        CACHE_ESTADOS[dev["ip"]]["online"] = True
        except Exception:
            pass

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        list(executor.map(enviar_comando, dispositivos))
        
    return jsonify({"success": True})

# --- Rutas de Programaciones ---

@app.route("/api/programaciones", methods=["GET"])
def obtener_programaciones():
    return jsonify(cargar_programaciones())

@app.route("/api/programaciones/crear", methods=["POST"])
def crear_programacion():
    data = request.json or {}
    ip = data.get("ip")
    accion = data.get("accion")
    hora = data.get("hora")
    dias = data.get("dias", [])
    
    if not ip or not accion or not hora:
        return jsonify({"success": False, "error": "Faltan parámetros requeridos"}), 400
        
    programaciones = cargar_programaciones()
    nueva = {
        "id": str(uuid.uuid4())[:8],
        "ip": ip,
        "accion": accion,
        "hora": hora,
        "dias": dias,
        "activo": True
    }
    programaciones.append(nueva)
    guardar_programaciones(programaciones)
    return jsonify({"success": True, "programacion": nueva})

@app.route("/api/programaciones/toggle", methods=["POST"])
def toggle_programacion():
    data = request.json or {}
    id_prog = data.get("id")
    if not id_prog:
        return jsonify({"success": False, "error": "ID de programación requerida"}), 400
        
    programaciones = cargar_programaciones()
    encontrado = False
    for p in programaciones:
        if p["id"] == id_prog:
            p["activo"] = not p.get("activo", True)
            encontrado = True
            break
            
    if not encontrado:
        return jsonify({"success": False, "error": "Programación no encontrada"}), 404
        
    guardar_programaciones(programaciones)
    return jsonify({"success": True})

@app.route("/api/programaciones/eliminar", methods=["POST"])
def eliminar_programacion():
    data = request.json or {}
    id_prog = data.get("id")
    if not id_prog:
        return jsonify({"success": False, "error": "ID de programación requerida"}), 400
        
    programaciones = cargar_programaciones()
    filtradas = [p for p in programaciones if p["id"] != id_prog]
    
    if len(programaciones) == len(filtradas):
        return jsonify({"success": False, "error": "Programación no encontrada"}), 404
        
    guardar_programaciones(filtradas)
    return jsonify({"success": True})

@app.route("/api/colores", methods=["GET", "POST"])
def gestionar_colores():
    if request.method == "GET":
        return jsonify(cargar_colores())
    
    data = request.json or {}
    nombre = data.get("nombre")
    r = data.get("r")
    g = data.get("g")
    b = data.get("b")
    
    if nombre is None or r is None or g is None or b is None:
        return jsonify({"success": False, "error": "Faltan parámetros del color"}), 400
        
    colores = cargar_colores()
    colores = [c for c in colores if c["nombre"].lower() != nombre.lower()]
    colores.append({
        "nombre": nombre,
        "r": int(r),
        "g": int(g),
        "b": int(b)
    })
    guardar_colores(colores)
    return jsonify({"success": True})

@app.route("/api/colores/eliminar", methods=["POST"])
def eliminar_color():
    data = request.json or {}
    nombre = data.get("nombre")
    if not nombre:
        return jsonify({"success": False, "error": "Nombre del color requerido"}), 400
        
    colores = cargar_colores()
    colores_filtrados = [c for c in colores if c["nombre"].lower() != nombre.lower()]
    
    if len(colores) == len(colores_filtrados):
        return jsonify({"success": False, "error": "Color no encontrado"}), 404
        
    guardar_colores(colores_filtrados)
    return jsonify({"success": True})

if __name__ == "__main__":
    # Escucha en todas las interfaces para permitir acceso desde el celular
    app.run(host="0.0.0.0", port=5000, debug=True)
