# SmartHome Pro 💡🔌

SmartHome Pro es un sistema de control local descentralizado para dispositivos inteligentes del ecosistema **Surplife / MagicHome / Zengge** (focos LED RGBCW y enchufes inteligentes) a través de la red WiFi local, con **cero dependencia de la nube** y funcionamiento 100% autónomo.

El repositorio cuenta con dos interfaces de control independientes:
1. **Aplicación Web en Python (Flask):** Servidor local ligero para el navegador.
2. **Aplicación Móvil Android (Capacitor 6):** App móvil híbrida autónoma (toda la lógica de control y tareas programadas corre en el smartphone).

---

## 🚀 Arquitectura y Componentes

| Componente | Punto de Entrada | Caso de Uso |
|:---|:---|:---|
| **Python CLI** | `main.py` | Control rápido por terminal de comandos |
| **Aplicación Web** | `web_app.py` | Interfaz web para navegadores en la red local |
| **App de Android** | `SmartHomePro-Android/` | Aplicación móvil nativa para smartphones |

---

## 🛠️ Instalación y Ejecución

### 1. Servidor Web Python (Flask)
Requiere Python 3.8+ y las librerías `flux-led` y `flask`:
```bash
pip install flask flux-led
python web_app.py
```
Accede desde tu navegador en: `http://localhost:5000` (o la IP local del PC en tu red).

### 2. Aplicación Android (Capacitor 6)
La app compila el código web en la carpeta `www` y utiliza un bridge Java nativo para los sockets TCP/UDP y el `AlarmManager` de Android.
```bash
cd SmartHomePro-Android
npm install

# Sincronizar cambios web con el proyecto nativo
npx cap sync android

# Compilar APK Debug (desde directorio SmartHomePro-Android/android)
cd android
./gradlew assembleDebug
```

---

## 🔒 Mejoras y Corrección de Bugs Realizadas

Recientemente se completó una auditoría y corrección de 8 fallos de estabilidad en la aplicación móvil Android:

1. **Bug 1 (Temporizadores en segundo plano):** Los temporizadores `once` no se desactivan en reposo. Se habilitó el soporte completo de WakeLocks y bypass de fechas para tareas programadas de ejecución única.
2. **Bug 2 (Inyección de JavaScript):** Se crearon funciones de sanitización (`escHTML` y `escJS`) para escapar caracteres especiales como comillas simples (`'`), evitando inyecciones de código o fallos en el renderizado de dispositivos con nombres especiales (ej. *O'Clock*).
3. **Bug 3 (Pantalla blanca por localStorage corrupto):** Se envolvieron con bloques `try/catch` seguros las funciones de parsing JSON local, protegiendo a la app de un cuelgue definitivo al iniciar.
4. **Bug 4 (Optimización de batería):** Se configuró el permiso `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` para mostrar el popup del sistema directo del paquete de la app, facilitando al usuario otorgar la exención de ahorro de energía.
5. **Bug 5 (Colisiones de Alarmas):** Se reemplazó el `id.hashCode()` por un sistema de identificadores numéricos incrementales en `SharedPreferences`, evitando que tareas programadas independientes se sobrescriban mutuamente en el `AlarmManager`.
6. **Bug 6 (Carrera en reiniciar timer):** Se transformó `iniciarTimer` en asíncrono para esperar (`await`) la finalización nativa del borrado del temporizador anterior antes de agendar el nuevo.
7. **Bug 7 (Doble Polling):** Se optimizó la carga inicial removiendo consultas TCP duplicadas al iniciar la app, reduciendo el tráfico inalámbrico a la mitad.
8. **Bug 8 (Carga de plugin Capacitor):** Se actualizó la detección del bridge nativo para esperar el registro completo del plugin `SocketBridge` antes de habilitar los botones de la interfaz gráfica.

---

## 📚 Documentación Adicional
*   [Documentación Técnica de la App Android](Documentación%20App%20Android%20-%20SmartHome%20Pro.md)
*   [Documentación del Protocolo de Control Surplife](Documentación%20de%20Control%20Local%20-%20Dispositivos%20Surplife.md)
