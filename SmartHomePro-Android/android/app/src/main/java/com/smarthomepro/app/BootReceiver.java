package com.smarthomepro.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                SharedPreferences prefs = context.getSharedPreferences("smart_home_alarms", Context.MODE_PRIVATE);
                Set<String> ids = prefs.getStringSet("alarm_ids", new HashSet<String>());
                if (ids == null || ids.isEmpty()) return;

                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (alarmManager == null) return;

                for (String id : ids) {
                    boolean active = prefs.getBoolean(id + "_active", false);
                    if (!active) continue;

                    String ip = prefs.getString(id + "_ip", null);
                    String action = prefs.getString(id + "_action", null);
                    String daysStr = prefs.getString(id + "_days", "");

                    if (ip == null || action == null) continue;

                    Intent alarmIntent = new Intent(context, AlarmReceiver.class);
                    alarmIntent.putExtra("id", id);
                    alarmIntent.putExtra("ip", ip);
                    alarmIntent.putExtra("action", action);
                    alarmIntent.putExtra("days", daysStr);

                    long triggerTime;
                    if ("once".equals(daysStr)) {
                        triggerTime = prefs.getLong(id + "_triggerTime", -1);
                        if (triggerTime == -1 || triggerTime <= System.currentTimeMillis()) {
                            // Limpiar alarma expirada
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.remove(id + "_ip");
                            editor.remove(id + "_action");
                            editor.remove(id + "_days");
                            editor.remove(id + "_triggerTime");
                            editor.remove(id + "_active");
                            Set<String> newIds = new HashSet<>(ids);
                            newIds.remove(id);
                            editor.putStringSet("alarm_ids", newIds);
                            editor.apply();
                            continue;
                        }
                    } else {
                        int hour = prefs.getInt(id + "_hour", -1);
                        int minute = prefs.getInt(id + "_minute", -1);
                        if (hour == -1 || minute == -1) continue;

                        alarmIntent.putExtra("hour", hour);
                        alarmIntent.putExtra("minute", minute);

                        Calendar cal = Calendar.getInstance();
                        cal.set(Calendar.HOUR_OF_DAY, hour);
                        cal.set(Calendar.MINUTE, minute);
                        cal.set(Calendar.SECOND, 0);
                        cal.set(Calendar.MILLISECOND, 0);

                        // Si la hora ya pasó hoy (o es ahora mismo), agendar para mañana
                        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                            cal.add(Calendar.DAY_OF_YEAR, 1);
                        }

                        triggerTime = cal.getTimeInMillis();
                    }

                    int requestCode = prefs.getInt(id + "_requestCode", -1);
                    if (requestCode == -1) {
                        requestCode = prefs.getInt("next_request_code", 1);
                        prefs.edit().putInt(id + "_requestCode", requestCode).putInt("next_request_code", requestCode + 1).apply();
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        flags |= PendingIntent.FLAG_IMMUTABLE;
                    }

                    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, alarmIntent, flags);

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
                }
            } catch (Exception ignored) {
                // Prevenir que el BootReceiver falle catastróficamente
            }
        }
    }
}
