package com.zeko.droid;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/** Watchdog: AlarmManager nhắc lại mỗi 15 phút, service chết thì bật lại (giống WatchdogReceiver của FasonRat). */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final long PERIOD_MS = 15 * 60 * 1000L;

    @Override public void onReceive(Context ctx, Intent intent) {
        try {
            Intent svc = new Intent(ctx, Core.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable ignored) { }
        schedule(ctx);
    }

    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, WatchdogReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i, flags);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + PERIOD_MS, PERIOD_MS, pi);
        } catch (Throwable ignored) { }
    }
}