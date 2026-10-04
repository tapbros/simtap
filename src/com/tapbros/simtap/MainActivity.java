package com.tapbros.simtap;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/** 설정 안내: 접근성 켜짐 여부, 제한된 설정 안내, 위젯 추가, 상태 읽어 오기. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        findViewById(R.id.btn_accessibility).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_app_info).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));
        findViewById(R.id.btn_pin_widget).setOnClickListener(v -> pinWidget());
        findViewById(R.id.btn_read_state).setOnClickListener(v -> readState());
        ((TextView) findViewById(R.id.footer)).setText(getString(R.string.main_footer, versionName()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceEnabled();
        TextView t = (TextView) findViewById(R.id.service_state);
        t.setText(on ? R.string.service_on : R.string.service_off);
        t.setTextColor(getColor(on ? R.color.ok : R.color.warn));
    }

    private void pinWidget() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        if (!mgr.isRequestPinAppWidgetSupported()
                || !mgr.requestPinAppWidget(new ComponentName(this, SimTapWidget.class), null, null)) {
            Toast.makeText(this, R.string.toast_pin_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    /** arm 없이 SIM 관리자 화면만 연다. 서비스가 화면을 보고 캐시와 위젯을 갱신한다. */
    private void readState() {
        // 위젯 탭 직후 남은 arm 이 있으면 스위치가 눌리므로 열기 전에 지운다.
        SimTapService.cancel(this);
        try {
            startActivity(TrampolineActivity.simManagerIntent());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.toast_open_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.toast_no_app, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isServiceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName cn = new ComponentName(this, SimTapService.class);
        for (String s : list.split(":")) {
            if (s.equalsIgnoreCase(cn.flattenToString()) || s.equalsIgnoreCase(cn.flattenToShortString())) return true;
        }
        return false;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
