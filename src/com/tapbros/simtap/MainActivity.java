package com.tapbros.simtap;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/** 설정 안내: 접근성 켜짐 여부, 제한된 설정 안내, 위젯 추가 방법, 상태 읽어 오기. */
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
        findViewById(R.id.btn_read_state).setOnClickListener(v -> readState());
        ((TextView) findViewById(R.id.footer)).setText(getString(R.string.main_footer, versionName()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        SimTapWidget.refresh(this);
        boolean on = isServiceEnabled();
        TextView t = (TextView) findViewById(R.id.service_state);
        t.setText(on ? R.string.service_on : R.string.service_off);
        t.setTextColor(getColor(on ? R.color.ok : R.color.warn));
        ((TextView) findViewById(R.id.cache_state)).setText(cacheSummary());
    }

    /** 위젯이 그리는 캐시를 한 줄로. 예: 「SIM 1: 켜짐 · eSIM 회사: 꺼짐 · 데이터: SKT」. */
    private String cacheSummary() {
        int lines = SimCache.lineCount(this);
        boolean data = SimCache.dataShown(this);
        if (lines == 0 && !data) return getString(R.string.cache_none);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            String name = SimCache.name(this, i);
            if (name == null || name.isEmpty()) name = getString(R.string.sim_default, i + 1);
            int on = SimCache.on(this, i);
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(R.string.cache_item, name,
                    getString(on == 1 ? R.string.state_on : on == 0 ? R.string.state_off : R.string.state_unknown)));
        }
        if (data) {
            String cur = SimCache.dataName(this);
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(R.string.cache_item, getString(R.string.data_label),
                    cur.isEmpty() ? getString(R.string.state_unknown) : cur));
        }
        return sb.toString();
    }

    /** arm 없이 SIM 관리자 화면만 연다. 서비스가 화면을 보고 캐시와 위젯을 갱신한다. */
    private void readState() {
        // 서비스가 꺼져 있으면 열어도 캐시가 채워지지 않는다.
        if (!SimTapService.isRunning()) {
            Toast.makeText(this, R.string.toast_service_off_here, Toast.LENGTH_LONG).show();
            return;
        }
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
