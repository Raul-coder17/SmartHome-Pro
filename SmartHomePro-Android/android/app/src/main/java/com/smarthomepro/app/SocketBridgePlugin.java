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
import android.os.Build;
import java.util.Calendar;

@CapacitorPlugin(name = "SocketBridge")
public class SocketBridgePlugin extends Plugin {

    @PluginMethod
    public void sendTcpCommand(PluginCall call) {
        String ip = call.getString("ip");
        JSArray bytesArray = call.getArray("bytes");

        if (ip == null || bytesArray == null) {
            call.reject("IP o bytes inválidos");
            return;
        }

        // Ejecutar en hilo secundario para no bloquear el hilo principal de la UI
        getBridge().executeOnExecutor(new Runnable() {
            @Override
            public void run() {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(ip, 5577), 1500);
                    socket.setSoTimeout(1500);

                    byte[] data = new byte[bytesArray.length()];
                    for (int i = 0; i < bytesArray.length(); i++) {
                        data[i] = (byte) bytesArray.getInt(i);
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

        getBridge().executeOnExecutor(new Runnable() {
            @Override
            public void run() {
                Socket socket = null;
                JSObject result = new JSObject();
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(ip, 5577), 1500);
                    socket.setSoTimeout(1500);

                    // Envía comando de consulta de estado [0x81, 0x8a, 0x8b, 0x96]
                    byte[] query = new byte[]{(byte) 0x81, (byte) 0x8a, (byte) 0x8b, (byte) 0x96};
                    OutputStream out = socket.getOutputStream();
                    out.write(query);
                    out.flush();

                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[32];
                    int read = in.read(buffer);

                    if (read >= 4 && (buffer[0] & 0xFF) == 0x81) {
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
        });
    }

    @PluginMethod
    public void scanNetwork(PluginCall call) {
        int timeout = call.getInt("timeout", 2000);

        getBridge().executeOnExecutor(new Runnable() {
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

                    while (System.currentTimeMillis() - startTime < timeout) {
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

        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("id", id);
        intent.putExtra("ip", ip);
        intent.putExtra("action", action);
        intent.putExtra("days", daysStr);
        intent.putExtra("hour", hour);
        intent.putExtra("minute", minute);

        int requestCode = id.hashCode();
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

        // Si la hora ya pasó hoy, agendar para mañana
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        long triggerTime = cal.getTimeInMillis();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
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
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            call.reject("AlarmManager no disponible");
            return;
        }

        Intent intent = new Intent(context, AlarmReceiver.class);
        int requestCode = id.hashCode();
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
}
