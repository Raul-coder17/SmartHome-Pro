package com.smarthomepro.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.PowerManager;
import android.provider.Settings;
import android.net.Uri;


@CapacitorPlugin(name = "SocketBridge")
public class SocketBridgePlugin extends Plugin {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> ipLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object getLockForIp(String ip) {
        return ipLocks.computeIfAbsent(ip, k -> new Object());
    }

    private Socket connectWithRetry(String ip, int port, int timeoutMs, int maxAttempts) throws Exception {
        Socket socket = null;
        int baseDelay = 100;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                return socket;
            } catch (Exception e) {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                if (attempt == maxAttempts) {
                    throw e;
                }
                int delay = (baseDelay * attempt) + (int)(Math.random() * 50);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new Exception("Conexión interrumpida");
                }
            }
        }
        throw new Exception("No se pudo conectar");
    }

    @PluginMethod
    public void sendTcpCommand(PluginCall call) {
        String ip = call.getString("ip");
        JSArray bytesArray = call.getArray("bytes");

        if (ip == null || bytesArray == null) {
            call.reject("IP o bytes inválidos");
            return;
        }

        // Validación de formato IP (IPv4 estándar)
        if (!ip.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")) {
            call.reject("Formato de IP inválido o malformado");
            return;
        }

        // Validación de tamaño seguro para evitar OutOfMemory o DoS de bombilla
        if (bytesArray.length() == 0 || bytesArray.length() > 32) {
            call.reject("Tamaño de payload no permitido (debe ser entre 1 y 32 bytes)");
            return;
        }

        // Ejecutar en hilo secundario para no bloquear el hilo principal de la UI
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // Sincronizar por IP para encolar comandos concurrentes
                synchronized (getLockForIp(ip)) {
                    Socket socket = null;
                    try {
                        // Conexión con reintentos para mitigar colisiones (1.5s timeout, 2 intentos)
                        socket = connectWithRetry(ip, 5577, 1500, 2);

                        byte[] data = new byte[bytesArray.length()];
                        for (int i = 0; i < bytesArray.length(); i++) {
                            int bVal = bytesArray.getInt(i);
                            if (bVal < 0 || bVal > 255) {
                                call.reject("Byte fuera de rango en la posición " + i + ": " + bVal);
                                return;
                            }
                            data[i] = (byte) bVal;
                        }

                        OutputStream out = socket.getOutputStream();
                        out.write(data);
                        out.flush();

                        call.resolve();
                    } catch (Exception e) {
                        call.reject("Error al enviar comando TCP: " + e.getMessage());
                    } finally {
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        });
    }

    @PluginMethod
    public void checkDeviceStatus(PluginCall call) {
        String ip = call.getString("ip");

        if (ip == null) {
            JSObject result = new JSObject();
            result.put("online", false);
            result.put("encendido", false);
            call.resolve(result);
            return;
        }

        if (!ip.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")) {
            JSObject result = new JSObject();
            result.put("online", false);
            result.put("encendido", false);
            call.resolve(result);
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                // Sincronizar por IP para evitar conflictos de lectura simultáneos
                synchronized (getLockForIp(ip)) {
                    Socket socket = null;
                    JSObject result = new JSObject();
                    try {
                        // Consultar estado con reintentos para mayor fiabilidad (2.0s timeout, 2 intentos)
                        socket = connectWithRetry(ip, 5577, 2000, 2);

                        // Envía comando de consulta de estado [0x81, 0x8a, 0x8b, 0x96]
                        byte[] query = new byte[]{(byte) 0x81, (byte) 0x8a, (byte) 0x8b, (byte) 0x96};
                        OutputStream out = socket.getOutputStream();
                        out.write(query);
                        out.flush();

                        InputStream in = socket.getInputStream();
                        byte[] buffer = new byte[32];
                        int totalRead = 0;
                        while (totalRead < 4) {
                            int read = in.read(buffer, totalRead, buffer.length - totalRead);
                            if (read == -1) {
                                break;
                            }
                            totalRead += read;
                        }

                        if (totalRead >= 4 && (buffer[0] & 0xFF) == 0x81) {
                            int powerByte = buffer[2] & 0xFF;
                            boolean isOn = (powerByte == 0x23); // 0x23 = ON, 0x24 = OFF
                            result.put("online", true);
                            result.put("encendido", isOn);
                        } else {
                            result.put("online", false);
                            result.put("encendido", false);
                        }
                    } catch (Exception e) {
                        result.put("online", false);
                        result.put("encendido", false);
                    } finally {
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (Exception ignored) {}
                        }
                        call.resolve(result);
                    }
                }
            }
        });
    }

    @PluginMethod
    public void scanNetwork(PluginCall call) {
        int timeout = call.getInt("timeout", 2000);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                DatagramSocket socket = null;
                JSArray foundDevices = new JSArray();
                Set<String> uniqueIps = new HashSet<>();

                try {
                    socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    socket.setSoTimeout(timeout);

                    byte[] sendData = "HF-A11ASSISTHREAD".getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(
                            sendData,
                            sendData.length,
                            InetAddress.getByName("255.255.255.255"),
                            48899
                    );

                    socket.send(sendPacket);

                    byte[] receiveBuffer = new byte[1024];
                    long startTime = System.currentTimeMillis();

                    while (true) {
                        long remaining = timeout - (System.currentTimeMillis() - startTime);
                        if (remaining <= 0) {
                            break;
                        }
                        socket.setSoTimeout((int) remaining);

                        DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        try {
                            socket.receive(receivePacket);
                            String data = new String(receivePacket.getData(), 0, receivePacket.getLength()).trim();
                            
                            // Formato esperado: IP,Mac,Model (ej. 192.168.1.2,ACC8E0010203,HF-LPB100)
                            if (data.contains(",")) {
                                String[] parts = data.split(",");
                                if (parts.length >= 2) {
                                    String ip = parts[0].trim();
                                    String mac = parts[1].trim();
                                    String model = parts.length >= 3 ? parts[2].trim() : "Surplife Device";

                                    // Evitar duplicados
                                    if (!uniqueIps.contains(ip) && !ip.equals("+ok")) {
                                        uniqueIps.add(ip);
                                        JSObject dev = new JSObject();
                                        dev.put("ip", ip);
                                        dev.put("mac", mac);
                                        dev.put("model", model);
                                        foundDevices.put(dev);
                                    }
                                }
                            }
                        } catch (java.net.SocketTimeoutException e) {
                            // Fin del tiempo de escaneo
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Ignorar errores menores del socket, devolver lo que tengamos
                } finally {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                    JSObject response = new JSObject();
                    response.put("devices", foundDevices);
                    call.resolve(response);
                }
            }
        });
    }

    @PluginMethod
    public void setAlarm(PluginCall call) {
        String id = call.getString("id");
        String ip = call.getString("ip");
        String action = call.getString("action");
        Integer hour = call.getInt("hour");
        Integer minute = call.getInt("minute");
        JSArray daysArray = call.getArray("days");

        if (id == null || ip == null || action == null || hour == null || minute == null) {
            call.reject("Parámetros incompletos para la alarma");
            return;
        }

        StringBuilder daysBuilder = new StringBuilder();
        if (daysArray != null) {
            for (int i = 0; i < daysArray.length(); i++) {
                try {
                    if (i > 0) daysBuilder.append(",");
                    daysBuilder.append(daysArray.getString(i));
                } catch (Exception ignored) {}
            }
        }
        String daysStr = daysBuilder.toString();

        Context context = getContext();
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            call.reject("AlarmManager no disponible");
            return;
        }

        // Sincronización en SharedPreferences
        try {
            SharedPreferences prefs = context.getSharedPreferences("smart_home_alarms", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(id + "_ip", ip);
            editor.putString(id + "_action", action);
            editor.putString(id + "_days", daysStr);
            editor.putInt(id + "_hour", hour);
            editor.putInt(id + "_minute", minute);
            editor.putBoolean(id + "_active", true);

            Set<String> ids = prefs.getStringSet("alarm_ids", new HashSet<String>());
            Set<String> newIds = new HashSet<>(ids);
            newIds.add(id);
            editor.putStringSet("alarm_ids", newIds);
            editor.apply();
        } catch (Exception e) {
            // Ignorar errores de guardado local, no bloquear registro de alarma
        }

        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("id", id);
        intent.putExtra("ip", ip);
        intent.putExtra("action", action);
        intent.putExtra("days", daysStr);
        intent.putExtra("hour", hour);
        intent.putExtra("minute", minute);

        int requestCode = getRequestCode(context, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // Si la hora ya pasó hoy (o es ahora mismo), agendar para mañana
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        long triggerTime = cal.getTimeInMillis();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
        } catch (SecurityException e) {
            // Fallback si el permiso de alarmas exactas fue revocado por el usuario
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
        }

        call.resolve();
    }

    @PluginMethod
    public void cancelAlarm(PluginCall call) {
        String id = call.getString("id");
        if (id == null) {
            call.reject("ID de alarma faltante");
            return;
        }

        Context context = getContext();
        
        // Sincronización en SharedPreferences
        try {
            SharedPreferences prefs = context.getSharedPreferences("smart_home_alarms", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(id + "_ip");
            editor.remove(id + "_action");
            editor.remove(id + "_days");
            editor.remove(id + "_hour");
            editor.remove(id + "_minute");
            editor.remove(id + "_active");
            editor.remove(id + "_requestCode");

            Set<String> ids = prefs.getStringSet("alarm_ids", new HashSet<String>());
            if (ids.contains(id)) {
                Set<String> newIds = new HashSet<>(ids);
                newIds.remove(id);
                editor.putStringSet("alarm_ids", newIds);
            }
            editor.apply();
        } catch (Exception e) {
            // Ignorar errores de persistencia al cancelar
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            call.reject("AlarmManager no disponible");
            return;
        }

        Intent intent = new Intent(context, AlarmReceiver.class);
        int requestCode = getRequestCode(context, id);
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags);
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }

        call.resolve();
    }

    @PluginMethod
    public void setTimer(PluginCall call) {
        String id = call.getString("id");
        String ip = call.getString("ip");
        String action = call.getString("action");
        Long triggerTime = call.getLong("triggerTime");

        if (id == null || ip == null || action == null || triggerTime == null) {
            call.reject("Parámetros incompletos para el temporizador");
            return;
        }

        Context context = getContext();
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            call.reject("AlarmManager no disponible");
            return;
        }

        // Guardar en SharedPreferences
        try {
            SharedPreferences prefs = context.getSharedPreferences("smart_home_alarms", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(id + "_ip", ip);
            editor.putString(id + "_action", action);
            editor.putString(id + "_days", "once");
            editor.putLong(id + "_triggerTime", triggerTime);
            editor.putBoolean(id + "_active", true);

            Set<String> ids = prefs.getStringSet("alarm_ids", new HashSet<String>());
            Set<String> newIds = new HashSet<>(ids);
            newIds.add(id);
            editor.putStringSet("alarm_ids", newIds);
            editor.apply();
        } catch (Exception e) {
            // Ignorar errores de guardado local
        }

        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("id", id);
        intent.putExtra("ip", ip);
        intent.putExtra("action", action);
        intent.putExtra("days", "once");

        int requestCode = getRequestCode(context, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
        }

        call.resolve();
    }

    @PluginMethod
    public void isBatteryOptimizationIgnoring(PluginCall call) {
        Context context = getContext();
        boolean ignoring = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                ignoring = pm.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        }
        JSObject result = new JSObject();
        result.put("ignoring", ignoring);
        call.resolve(result);
    }

    @PluginMethod
    public void requestIgnoreBatteryOptimizations(PluginCall call) {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + context.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception ignored) {}
                }
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void canScheduleExactAlarms(PluginCall call) {
        Context context = getContext();
        boolean canSchedule = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                canSchedule = alarmManager.canScheduleExactAlarms();
            }
        }
        JSObject result = new JSObject();
        result.put("canSchedule", canSchedule);
        call.resolve(result);
    }

    @PluginMethod
    public void requestExactAlarmPermission(PluginCall call) {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
        call.resolve();
    }

    private static int getRequestCode(Context context, String id) {
        SharedPreferences prefs = context.getSharedPreferences("smart_home_alarms", Context.MODE_PRIVATE);
        int requestCode = prefs.getInt(id + "_requestCode", -1);
        if (requestCode == -1) {
            requestCode = prefs.getInt("next_request_code", 1);
            prefs.edit()
                 .putInt(id + "_requestCode", requestCode)
                 .putInt("next_request_code", requestCode + 1)
                 .apply();
        }
        return requestCode;
    }
}
