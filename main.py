import time
from src.device import ControladorSurplife
from src.config import cargar_dispositivos, guardar_dispositivos
from src.discovery import escanear_red

def menu_controlar_dispositivo(dev):
    """Muestra el menú de opciones para controlar un dispositivo específico."""
    ip = dev["ip"]
    nombre = dev["nombre"]
    tipo = dev["tipo"]
    
    print(f"\nConectando a {nombre} ({ip})...")
    controlador = ControladorSurplife(ip, silent=False)
    
    if not controlador.dispositivo:
        print("[ERROR] No se pudo establecer la conexión. Revisa que esté encendido físicamente.")
        input("\nPresiona Enter para regresar...")
        return

    while True:
        print("\n" + "="*40)
        print(f"CONTROL DE: {nombre.upper()} ({tipo.upper()})")
        print("="*40)
        print("1. Encender")
        print("2. Apagar")
        
        if tipo == "foco":
            print("3. Cambiar Color RGB")
            print("4. Cambiar Luz Blanca (Frio/Calido)")
            print("5. Regresar al menu principal")
        else:
            print("3. Regresar al menu principal")
            
        opcion = input("\nSelecciona una opcion: ").strip()
        
        if opcion == "1":
            controlador.encender()
        elif opcion == "2":
            controlador.apagar()
        elif tipo == "foco" and opcion == "3":
            try:
                print("\nIngresa los valores RGB (0 a 255):")
                r = int(input("Rojo (R): "))
                g = int(input("Verde (G): "))
                b = int(input("Azul (B): "))
                if 0 <= r <= 255 and 0 <= g <= 255 and 0 <= b <= 255:
                    controlador.cambiar_color_rgb(r, g, b)
                else:
                    print("[ERROR] Los valores deben estar entre 0 y 255.")
            except ValueError:
                print("[ERROR] Entrada invalida. Debes ingresar numeros enteros.")
        elif tipo == "foco" and opcion == "4":
            try:
                calidez = int(input("\nCalidez (0 = Frio, 100 = Calido): "))
                intensidad = int(input("Intensidad (0% a 100%): "))
                if 0 <= calidez <= 100 and 0 <= intensidad <= 100:
                    controlador.cambiar_blanco(calidez, intensidad)
                else:
                    print("[ERROR] Los valores deben estar entre 0 y 100.")
            except ValueError:
                print("[ERROR] Entrada invalida. Debes ingresar numeros enteros.")
        elif (tipo == "foco" and opcion == "5") or (tipo == "enchufe" and opcion == "3"):
            print("Regresando...")
            break
        else:
            print("[ERROR] Opcion no valida.")
        
        time.sleep(1)

def agregar_dispositivo_manual(dispositivos):
    """Permite al usuario agregar un dispositivo manualmente."""
    print("\nAGREGAR DISPOSITIVO MANUALMENTE")
    nombre = input("Nombre descriptivo (ej. Foco Sala): ").strip()
    ip = input("Direccion IP (ej. 192.168.1.2): ").strip()
    
    print("Tipo de dispositivo:")
    print("1. Foco (Bulb RGBCW)")
    print("2. Enchufe (Smart Plug)")
    tipo_opt = input("Selecciona tipo (1 o 2): ").strip()
    
    tipo = "foco" if tipo_opt == "1" else "enchufe"
    
    if nombre and ip:
        dispositivos.append({
            "nombre": nombre,
            "ip": ip,
            "tipo": tipo
        })
        guardar_dispositivos(dispositivos)
        print("[OK] Dispositivo agregado con exito.")
    else:
        print("[ERROR] Nombre o IP invalidos. Intentalo de nuevo.")

def autodetectar_y_agregar(dispositivos):
    """Busca dispositivos automáticamente en la red y los agrega."""
    detectados = escanear_red()
    if not detectados:
        print("[ALERTA] No se encontraron dispositivos compatibles en la red local.")
        input("\nPresiona Enter para continuar...")
        return
    
    # Filtrar los que ya están guardados por IP
    ips_registradas = {d["ip"] for d in dispositivos}
    nuevos = [d for d in detectados if d.get("ipaddr") not in ips_registradas]
    
    if not nuevos:
        print("[INFO] Todos los dispositivos detectados ya estan registrados.")
        input("\nPresiona Enter para continuar...")
        return
    
    print(f"\nSe encontraron {len(nuevos)} nuevos dispositivos:")
    for idx, dev in enumerate(nuevos, 1):
        print(f"{idx}. IP: {dev.get('ipaddr')} (ID: {dev.get('id')})")
        
    print(f"\n{len(nuevos) + 1}. Cancelar y regresar")
    
    try:
        seleccion = int(input("\nSelecciona el dispositivo que deseas agregar: "))
        if 1 <= seleccion <= len(nuevos):
            dev_elegido = nuevos[seleccion - 1]
            ip = dev_elegido.get("ipaddr")
            
            print(f"\nConectando temporalmente a {ip} para identificacion...")
            controlador = ControladorSurplife(ip, silent=True)
            
            if controlador.dispositivo:
                print(f"[OK] Conexion establecida con {ip}.")
                while True:
                    print(f"\n--- IDENTIFICAR DISPOSITIVO [{ip}] ---")
                    print("1. Encender (para ver cual fisico reacciona)")
                    print("2. Apagar")
                    print("3. Registrar dispositivo (guardar en base de datos)")
                    print("4. Cancelar y regresar")
                    
                    sub_opt = input("\nSelecciona una opcion: ").strip()
                    if sub_opt == "1":
                        controlador.encender()
                    elif sub_opt == "2":
                        controlador.apagar()
                    elif sub_opt == "3":
                        print(f"\nConfigurando dispositivo con IP: {ip}")
                        nombre = input("Asigna un nombre descriptivo: ").strip()
                        
                        print("Tipo de dispositivo:")
                        print("1. Foco (Bulb RGBCW)")
                        print("2. Enchufe (Smart Plug)")
                        tipo_opt = input("Selecciona tipo (1 o 2): ").strip()
                        tipo = "foco" if tipo_opt == "1" else "enchufe"
                        
                        if nombre:
                            dispositivos.append({
                                "nombre": nombre,
                                "ip": ip,
                                "tipo": tipo
                            })
                            guardar_dispositivos(dispositivos)
                            print("[OK] Dispositivo agregado con exito.")
                        else:
                            print("[ERROR] Nombre invalido.")
                        break
                    elif sub_opt == "4":
                        print("Operacion cancelada.")
                        break
                    else:
                        print("[ERROR] Opcion invalida.")
            else:
                print(f"[ALERTA] No se pudo conectar a {ip} para probarlo.")
                confirm = input("¿Deseas registrarlo directamente de todos modos? (s/n): ").strip().lower()
                if confirm == "s":
                    print(f"\nConfigurando dispositivo con IP: {ip}")
                    nombre = input("Asigna un nombre descriptivo: ").strip()
                    
                    print("Tipo de dispositivo:")
                    print("1. Foco (Bulb RGBCW)")
                    print("2. Enchufe (Smart Plug)")
                    tipo_opt = input("Selecciona tipo (1 o 2): ").strip()
                    tipo = "foco" if tipo_opt == "1" else "enchufe"
                    
                    if nombre:
                        dispositivos.append({
                            "nombre": nombre,
                            "ip": ip,
                            "tipo": tipo
                        })
                        guardar_dispositivos(dispositivos)
                        print("[OK] Dispositivo agregado con exito.")
                    else:
                        print("[ERROR] Nombre invalido.")
        else:
            print("Operacion cancelada.")
    except (ValueError, IndexError):
        print("[ERROR] Seleccion invalida.")
    
    input("\nPresiona Enter para continuar...")

def main():
    while True:
        dispositivos = cargar_dispositivos()
        
        print("\n" + "="*50)
        print("PANEL DE CONTROL DE DISPOSITIVOS HOGAR")
        print("="*50)
        
        if not dispositivos:
            print("[No hay dispositivos registrados en dispositivos.json]")
        else:
            print("Detectando estado actual en la red (Silencioso)...")
            for idx, dev in enumerate(dispositivos, 1):
                ctrl = ControladorSurplife(dev["ip"], silent=True)
                estado = "ONLINE" if ctrl.dispositivo else "OFFLINE"
                print(f"{idx}. {dev['nombre']} - {dev['ip']} ({dev['tipo'].upper()}) [{estado}]")
        
        print("-" * 50)
        print("A. Agregar dispositivo manualmente")
        print("B. Autodetectar dispositivos en la red")
        print("S. Salir del programa")
        print("-" * 50)
        
        opcion = input("Selecciona un numero para controlar, o una letra de opcion: ").strip().upper()
        
        if opcion == "S":
            print("\nAdios! Gracias por usar el controlador.")
            break
        elif opcion == "A":
            agregar_dispositivo_manual(dispositivos)
        elif opcion == "B":
            autodetectar_y_agregar(dispositivos)
        else:
            try:
                idx = int(opcion)
                if 1 <= idx <= len(dispositivos):
                    menu_controlar_dispositivo(dispositivos[idx - 1])
                else:
                    print("[ERROR] Numero de dispositivo fuera de rango.")
                    time.sleep(1)
            except ValueError:
                print("[ERROR] Opcion no reconocida.")
                time.sleep(1)

if __name__ == "__main__":
    main()
