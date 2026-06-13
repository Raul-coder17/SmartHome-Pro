from flux_led import WifiLedBulb

class ControladorSurplife:
    """Clase para la conexión y control directo de dispositivos inteligentes Surplife/Zengge."""
    
    def __init__(self, ip, silent=False):
        self.ip = ip
        try:
            self.dispositivo = WifiLedBulb(self.ip)
            self.dispositivo.refreshState()
        except Exception as e:
            if not silent:
                print(f"Error al conectar con {self.ip}: {e}")
            self.dispositivo = None

    def encender(self):
        if self.dispositivo:
            self.dispositivo.turnOn()
            print(f"[{self.ip}] Encendido")

    def apagar(self):
        if self.dispositivo:
            self.dispositivo.turnOff()
            print(f"[{self.ip}] Apagado")

    def cambiar_color_rgb(self, r, g, b):
        """
        Cambia el color del foco. Los valores de r, g, b deben estar entre 0 y 255.
        """
        if self.dispositivo:
            self.dispositivo.setRgb(r, g, b)
            print(f"[{self.ip}] Color cambiado a RGB({r}, {g}, {b})")

    def cambiar_blanco(self, calidez, intensidad):
        """
        Controla los LEDs blancos del foco.
        calidez: 0 (Blanco frío) a 100 (Blanco cálido)
        intensidad: 0 a 100
        """
        if self.dispositivo:
            valor_intensidad = int((intensidad / 100.0) * 255)
            if calidez > 50:
                self.dispositivo.setWarmWhite255(valor_intensidad)
                print(f"[{self.ip}] Luz blanca cálida activada al {intensidad}%")
            else:
                self.dispositivo.setColdWhite255(valor_intensidad)
                print(f"[{self.ip}] Luz blanca fría activada al {intensidad}%")
