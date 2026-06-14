package com.smarthomepro.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Calendar;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("id");
        String ip = intent.getStringExtra("ip");
        String action = intent.getStringExtra("action");
        String daysStr = intent.getStringExtra("days"); // Formato: "Lunes,Miércoles" o vacío (Diario)
        int hour = intent.getIntExtra("hour", -1);
        int minute = intent.getIntExtra("minute", -1);

        if (ip == null || action == null || hour == -1 || minute == -1) {
            return;
        }

        // Adquirir WakeLock para que el celular no se duerma a mitad de la transmisión de red
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        try {
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartHomePro:AlarmWakeLock");
                wakeLock.acquire(3000); // Expirar en 3 segundos máximo
            }
        } catch (Exception e) {
            // Ignorar fallas al adquirir WakeLock
        }

        final PowerManager.WakeLock finalWakeLock = wakeLock;

        // Verificar si la alarma debe correr hoy
        boolean debeCorrerHoy = false;
        Calendar ahora = Calendar.getInstance();
        int diaSemana = ahora.get(Calendar.DAY_OF_WEEK); // 1 = Domingo, 2 = Lunes, ..., 7 = Sábado

        if (daysStr == null || daysStr.isEmpty() || daysStr.equals("Diario")) {
            debeCorrerHoy = true;
        } else {
            String[] dias = daysStr.split(",");
            for (String d : dias) {
                String dTrim = d.trim().toLowerCase();
                if (dTrim.equals("domingo") && diaSemana == Calendar.SUNDAY) debeCorrerHoy = true;
                if (dTrim.equals("lunes") && diaSemana == Calendar.MONDAY) debeCorrerHoy = true;
                if (dTrim.equals("martes") && diaSemana == Calendar.TUESDAY) debeCorrerHoy = true;
                if (dTrim.equals("miércoles") && diaSemana == Calendar.WEDNESDAY) debeCorrerHoy = true;
                if (dTrim.equals("jueves") && diaSemana == Calendar.THURSDAY) debeCorrerHoy = true;
                if (dTrim.equals("viernes") && diaSemana == Calendar.FRIDAY) debeCorrerHoy = true;
                if (dTrim.equals("sábado") && diaSemana == Calendar.SATURDAY) debeCorrerHoy = true;
            }
        }

        if (debeCorrerHoy) {
            final BroadcastReceiver.PendingResult pendingResult = goAsync();
            // Enviar comando TCP en hilo secundario
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Socket socket = null;
                    try {
                        socket = new Socket();
                        socket.connect(new InetSocketAddress(ip, 5577), 2000);
                        socket.setSoTimeout(2000);

                        // Comando bytes ON/OFF
                        byte[] command = action.equals("encender")
                                ? new byte[]{(byte) 0x71, (byte) 0x23, (byte) 0x0f, (byte) 0xa3}
                                : new byte[]{(byte) 0x71, (byte) 0x24, (byte) 0x0f, (byte) 0xa4};

                        OutputStream out = socket.getOutputStream();
                        out.write(command);
                        out.flush();
                    } catch (Exception ignored) {
                        // Silenciar errores de red en segundo plano
                    } finally {
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (Exception ignored) {}
                        }
                        // Liberar WakeLock de forma segura
                        if (finalWakeLock != null) {
                            try {
                                if (finalWakeLock.isHeld()) {
                                    finalWakeLock.release();
                                }
                            } catch (Exception ignored) {}
                        }
                        // Finalizar el procesamiento asíncrono del receptor para liberar el hilo del OS
                        if (pendingResult != null) {
                            try {
                                pendingResult.finish();
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }).start();
        } else {
            // Liberar WakeLock de inmediato si no corre hoy
            if (finalWakeLock != null) {
                try {
                    if (finalWakeLock.isHeld()) {
                        finalWakeLock.release();
                    }
                } catch (Exception ignored) {}
            }
        }

        // Volver a agendar para mañana de forma exacta (re-scheduling recurrente)
        agendarSiguienteDia(context, id, ip, action, daysStr, hour, minute);
    }

    private void agendarSiguienteDia(Context context, String id, String ip, String action, String daysStr, int hour, int minute) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

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

        // Agendar para mañana a la misma hora (o el próximo día que toque a la misma hora)
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // Si la hora calculada ya pasó hoy (o es ahora mismo), agendar para mañana
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
    }
}
