# **Documentación Técnica: Control Local de Dispositivos Inteligentes Surplife**

Este documento proporciona una guía exhaustiva de la arquitectura de red y la implementación de software modular necesaria para la integración y el control local de los dispositivos inteligentes de la marca Vanance que operan bajo el ecosistema de la aplicación Surplife (fabricados por Zengge / MagicHome). 

La comunicación se realiza de forma directa en la red de área local (LAN) mediante sockets TCP/UDP, prescindiendo por completo de servidores en la nube para su funcionamiento básico.

---

## **1. Análisis de Dispositivos Soportados**

La infraestructura desarrollada da soporte a dos categorías principales de hardware inteligente distribuidos bajo la marca Vanance:

| Tipo de Dispositivo | Capacidades Operativas | Protocolo Local |
| :--- | :--- | :--- |
| **Enchufes Inteligentes (Smart Plugs)** | Control binario de estado (Encendido / Apagado). | TCP Puerto 5577 (Comandos de estado binario) |
| **Focos Inteligentes LED RGBCW** | Modulación de canales de color independientes (Red, Green, Blue) y ajuste de temperatura de blancos (Cold White, Warm White). | TCP Puerto 5577 (Comandos avanzados de 5 canales con Checksum) |

---

## **2. Requisitos y Dependencias del Sistema**

Para la ejecución del módulo de control se requiere Python 3.8 o superior. El proyecto utiliza la biblioteca `flux-led`, la cual provee la abstracción de sockets de bajo nivel para comunicarse con dispositivos basados en los chips controladores Zengge.

Instalación de las dependencias desde PyPI:
```bash
pip install flux-led
```

---

## **3. Estructura Modular del Proyecto**

El sistema ha sido estructurado como un paquete modular en Python para separar las preocupaciones de configuración de red, control de dispositivos, descubrimiento automático y la interfaz de usuario:

```
c:\controlador/
├── dispositivos.json        # Base de datos local (IPs, nombres y tipos)
├── main.py                  # Interfaz de usuario por consola (Punto de entrada)
└── src/                     # Paquete interno de funcionalidades
    ├── __init__.py          # Constructor del paquete
    ├── config.py            # Administración del archivo JSON y rutas de archivos
    ├── device.py            # Clase principal de control (ControladorSurplife)
    └── discovery.py         # Escaneo pasivo de red local (UDP broadcast)
```

---

## **4. Análisis Técnico del Código por Archivo**

### **A. Base de Datos Local: `dispositivos.json`**
Almacena un arreglo de objetos JSON que representan los dispositivos conocidos en la red.
*   **Campos clave**:
    *   `nombre`: Identificador amigable definido por el usuario.
    *   `ip`: Dirección IPv4 en la red local.
    *   `tipo`: Filtro de comportamiento (`foco` o `enchufe`).

---

### **B. Gestor de Configuración: `src/config.py`**
Maneja la persistencia y la lectura segura del archivo de datos.

*   **Ruta Relativa Dinámica**:
    ```python
    DIR_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    PATH_DISPOSITIVOS = os.path.join(DIR_BASE, "dispositivos.json")
    ```
    *   *Explicación*: Evita problemas de rutas rotas cuando el programa se ejecuta desde un directorio de trabajo distinto a la raíz del proyecto.
*   **`cargar_dispositivos()`**:
    *   Verifica si el archivo existe con `os.path.exists`.
    *   Abre el archivo con codificación `utf-8` y decodifica el JSON. Retorna una lista vacía si ocurre algún fallo.
*   **`guardar_dispositivos(dispositivos)`**:
    *   Serializa los objetos de Python a formato JSON indentado (`indent=2`) para mantenerlo legible para los humanos.

---

### **C. Controlador del Dispositivo: `src/device.py`**
Implementa la clase núcleo `ControladorSurplife` encargada de enviar comandos de red TCP.

*   **`__init__(self, ip, silent=False)`**:
    *   Instancia `WifiLedBulb(ip)` de la biblioteca `flux_led`.
    *   Establece una conexión TCP de socket al puerto `5577` del dispositivo e invoca a `refreshState()` para descargar el búfer de estado inicial.
    *   `silent=False` controla si los fallos de sockets (dispositivos apagados) deben imprimir errores en la terminal principal o ser ignorados durante las tareas de listado rápido.
*   **`encender(self)` y `apagar(self)`**:
    *   Llaman a `.turnOn()` y `.turnOff()` respectivamente. Envían tramas binarias TCP al dispositivo de manera directa.
*   **`cambiar_color_rgb(self, r, g, b)`**:
    *   Envía tramas de color mediante `.setRgb(r, g, b)`. Apaga los canales blancos internos para maximizar la saturación del espectro RGB.
*   **`cambiar_blanco(self, calidez, intensidad)`**:
    *   Convierte el porcentaje de intensidad (0-100%) a un byte escalado (0-255) requerido por el protocolo: `int((intensidad / 100.0) * 255)`.
    *   Dependiendo de la calidez (si es superior a 50), enciende el canal de luz blanca cálida (`.setWarmWhite255()`) o el canal de blanca fría (`.setColdWhite255()`).

---

### **D. Módulo de Descubrimiento: `src/discovery.py`**
Realiza la búsqueda de nuevos dispositivos sin necesidad de conocer su IP de antemano.

*   **`escanear_red()`**:
    *   **Detección Multi-adaptador**: Obtiene las IPs locales activas del equipo (incluyendo adaptadores VPN como Radmin VPN y adaptadores Wi-Fi) usando `socket.gethostbyname_ex` y crea un socket de difusión para cada uno.
    *   **Broadcast UDP**: Envía el payload estándar `b"HF-A11ASSISTHREAD"` a la IP de difusión global `255.255.255.255` en el puerto `48899`.
    *   **Prevención de Errores en Windows**: Configura un búfer de lectura amplio (`2048` bytes) en `sock.recvfrom` para evitar el error `WinError 10040 (WSAEMSGSIZE)` típico del socket de Winsock cuando la respuesta del dispositivo es superior al búfer estándar.
    *   **Filtrado de Duplicados**: Estructura las respuestas eliminando duplicados si el dispositivo responde en múltiples interfaces.

---

### **E. Interfaz de Usuario e Integrador: `main.py`**
Contiene el bucle de ejecución de consola (`main()`) y administra el flujo interactivo de control.

*   **Detección en Tiempo Real**:
    *   Al inicio de cada iteración del menú principal, inicializa de forma silenciosa (`silent=True`) cada dispositivo guardado en `dispositivos.json`. Si la conexión se establece con éxito, se muestra el estado `[ONLINE]`, de lo contrario, se marca como `[OFFLINE]`.
*   **`menu_controlar_dispositivo(dev)`**:
    *   Presenta menús contextuales adaptados según el `tipo` de dispositivo: los focos reciben opciones de cambio de color RGB y blancos, mientras que los enchufes solo muestran opciones binarias de encendido y apagado.
*   **`autodetectar_y_agregar(dispositivos)`**:
    *   Invoca al escáner de red y filtra los dispositivos ya registrados.
    *   **Submenú de Identificación**: Cuando el usuario selecciona un dispositivo detectado, se inicia una conexión de prueba silenciosa que permite encender y apagar el dispositivo de manera remota e interactiva (toggles de encendido/apagado). Esto ayuda al usuario a localizar físicamente cuál luz o enchufe reacciona.
    *   **Registro**: Tras identificarlo, permite asignarle un nombre descriptivo y definir su tipo (`foco` o `enchufe`) para escribirlo en `dispositivos.json`.

### **F. Servidor de API y Frontend Móvil: `web_app.py` & `templates/index.html`**
Proporciona la interfaz gráfica y los endpoints web optimizados para el control local mediante pantallas móviles (PWA).

*   **Peticiones en Paralelo**: Ejecuta consultas de estado de conexión concurrentes empleando `ThreadPoolExecutor` para evitar retrasar el renderizado web si algún foco está apagado físicamente.
*   **Optimizaciones de Red**: Aplica *throttling* (JS) en el slider del color para limitar las peticiones a un intervalo máximo de 150ms durante deslizamientos rápidos.
*   **Gestión de Colores Favoritos**: Almacena de forma persistente los colores en el archivo de texto estructurado `colores.json`.

---

## **5. Recomendaciones de Infraestructura y Red**

1.  **Asignación Estática mediante Servidor DHCP**: Dado que el protocolo requiere llamadas de red directas a direcciones IPv4 estables, se debe ingresar al enrutador WiFi y configurar reglas de enlace estático (DHCP Reservation) vinculando la dirección MAC de cada dispositivo inteligente a una IP constante. Esto evita que cambien de dirección tras un corte eléctrico.
2.  **Segmentación de Red IoT**: Para mejorar la seguridad general, se recomienda aislar estos dispositivos en una subred o red WiFi de Invitados (frecuencia de 2.4 GHz) e implementar reglas en el firewall que permitan la comunicación únicamente a través del puerto TCP `5577`.