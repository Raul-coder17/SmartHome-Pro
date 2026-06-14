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
                    int hour = prefs.getInt(id + "_hour", -1);
                    int minute = prefs.getInt(id + "_minute", -1);

                    if (ip == null || action == null || hour == -1 || minute == -1) continue;

                    Intent alarmIntent = new Intent(context, AlarmReceiver.class);
                    alarmIntent.putExtra("id", id);
                    alarmIntent.putExtra("ip", ip);
                    alarmIntent.putExtra("action", action);
                    alarmIntent.putExtra("days", daysStr);
                    alarmIntent.putExtra("hour", hour);
                    alarmIntent.putExtra("minute", minute);

                    int requestCode = id.hashCode();
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        flags |= PendingIntent.FLAG_IMMUTABLE;
                    }

                    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, alarmIntent, flags);

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
