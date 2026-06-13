import socket

def escanear_red():
    """
    Escanea la red local buscando dispositivos compatibles mediante UDP broadcast.
    Soporta múltiples adaptadores de red y evita errores de buffer (WinError 10040) en Windows.
    """
    print("\n[BUSCANDO] Escaneando la red local (esto puede tardar unos 5 segundos)...")
    
    # 1. Obtener todas las IPs locales asociadas a los adaptadores de red
    ips_locales = []
    try:
        host_name = socket.gethostname()
        ips_locales = socket.gethostbyname_ex(host_name)[2]
    except Exception:
        # Si falla, usamos la interfaz general
        ips_locales = ["0.0.0.0"]
        
    # Filtrar localhost
    ips_locales = [ip for ip in ips_locales if not ip.startswith("127.")]
    if not ips_locales:
        ips_locales = ["0.0.0.0"]
        
    dispositivos_detectados = []
    ips_encontradas = set() # Para evitar duplicados en caso de múltiples interfaces
    
    # 2. Enviar broadcast desde cada adaptador activo
    for ip_local in ips_locales:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(2.5) # Tiempo de espera para recibir respuestas
        
        try:
            # Enlaza a la IP del adaptador actual para forzar la salida de broadcast por esa interfaz
            sock.bind((ip_local, 0))
            
            # El payload de descubrimiento estándar para dispositivos Zengge/MagicHome/Surplife
            payload = b"HF-A11ASSISTHREAD"
            sock.sendto(payload, ("255.255.255.255", 48899))
            
            while True:
                try:
                    # Usamos un buffer de 2048 bytes para evitar WinError 10040 (WSAEMSGSIZE) en Windows
                    data, addr = sock.recvfrom(2048)
                    respuesta = data.decode("utf-8", errors="ignore").strip()
                    
                    # Formato esperado: "IP,MAC,Modelo,..."
                    parts = respuesta.split(',')
                    if len(parts) >= 2:
                        ip_disp = parts[0]
                        mac_disp = parts[1]
                        modelo_disp = parts[2] if len(parts) > 2 else "Desconocido"
                        
                        if ip_disp not in ips_encontradas:
                            ips_encontradas.add(ip_disp)
                            dispositivos_detectados.append({
                                "ipaddr": ip_disp,
                                "id": mac_disp,
                                "model": modelo_disp
                            })
                except socket.timeout:
                    break
                except Exception:
                    # En caso de otro error de socket, salimos del bucle para esa interfaz
                    break
        except Exception:
            # Si un adaptador falla en bind (ej. interfaces virtuales inactivas), pasamos al siguiente
            pass
        finally:
            sock.close()
            
    return dispositivos_detectados
