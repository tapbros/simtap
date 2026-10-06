package com.tapbros.simtap;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 위젯 칸 탭을 받아 arm(위젯 id, 칸, 목표값, SIM 이름, 시각)을 기록하고 SIM 관리자 화면을 연 뒤 바로 끝난다.
 * 5x1 위젯의 와이파이·데이터 칸은 시스템 인터넷 창을 연다. 일반 앱은 둘 다 직접 바꾸지 못하므로 서비스가 켜져 있고
 * 위젯이 보인 상태가 지금 상태와 같을 때만 서비스에 net 작업을 걸어 그 창의 스위치를 누르게 한다(prefs arm 없음).
 */
public class TrampolineActivity extends Activity {
    static final String PREFS = "arm";
    static final String ARMED_AT = "armedAt";
    static final String ARM_WIDGET = "armWidget";
    static final String ARM_SLOT = "armSlot";
    /** 회선 칸의 목표값: 칸이 보이던 상태의 반대(1 켜기, 0 끄기), 모르면 -1(토글). */
    static final String ARM_TARGET = "armTarget";
    /** 회선 칸: 탭할 때의 캐시 SIM 이름. 서비스는 화면 순번이 아니라 이 이름으로 스위치를 찾는다. */
    static final String ARM_NAME = "armName";
    static final String EXTRA_WIDGET = "widget";
    static final String EXTRA_SLOT = "slot";
    static final String EXTRA_TARGET = "target";
    /** 회선 칸이 보인 SIM 이름. 캐시 이름과 다르면 낡은 위젯이다. */
    static final String EXTRA_NAME = "name";
    /** SIM 관리자 화면 Intent. arm 하지 않으므로 여는 것만으로는 아무것도 누르지 않는다. 대상은 build.sh 가 만드는 TargetConfig 에서 온다. */
    static Intent simManagerIntent() {
        Intent i = new Intent();
        if (TargetConfig.SIM_MANAGER_ACTION != null) i.setAction(TargetConfig.SIM_MANAGER_ACTION);
        return i.setClassName(TargetConfig.PKG, TargetConfig.SIM_MANAGER_CLASS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int slot = getIntent().getIntExtra(EXTRA_SLOT, -1);
        if (slot == SimTapWidget.SLOT_WIFI || slot == SimTapWidget.SLOT_MOBILE) {
            boolean wifi = slot == SimTapWidget.SLOT_WIFI;
            // 위젯이 보낸 target 은 보인 상태의 반대다(SimTapWidget.net). 지금 상태의 반대와 같아야 누른다.
            int target = getIntent().getIntExtra(EXTRA_TARGET, -1);
            int cur = Build.VERSION.SDK_INT >= 29 && SimTapService.isRunning()
                    ? NetState.read(this, wifi ? NetState.WIFI : NetState.MOBILE) : -1;
            boolean stale = cur >= 0 && target != 1 - cur;
            if (stale) SimTapWidget.refresh(this);
            // 서비스 꺼짐, 상태 모름, 낡은 위젯, API 29 미만이면 작업을 걸지 않는다. 창만 열리고 아무것도 누르지 않는다.
            boolean job = cur >= 0 && !stale && SimTapService.startNet(slot, target);
            // 인터넷 창이 아닌 대체 화면이 열렸거나 아무것도 못 열었으면 누를 대상이 없다. 작업을 거두고 범위를 되돌린다.
            if (!openPanel(wifi) && job) SimTapService.cancel(this);
            finish();
            return;
        }
        if (slot < 0 || slot > SimTapWidget.SLOT_DATA) { finish(); return; }
        if (!SimTapService.isRunning()) {
            Toast.makeText(this, R.string.toast_service_off, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        int target = getIntent().getIntExtra(EXTRA_TARGET, -1);
        String name = slot == SimTapWidget.SLOT_DATA ? null : SimCache.name(this, slot);
        boolean unknown = slot != SimTapWidget.SLOT_DATA && (target < 0 || name == null || name.isEmpty());
        // 런처에 남은 낡은 위젯: 보인 이름이 캐시와 다르거나 target 이 현재 캐시 상태의 반대가 아니다.
        boolean stale = false;
        if (slot != SimTapWidget.SLOT_DATA && !unknown) {
            int on = SimCache.lineCount(this) > slot ? SimCache.on(this, slot) : -1;
            int expect = on == 1 ? 0 : on == 0 ? 1 : -1;
            stale = !name.equals(getIntent().getStringExtra(EXTRA_NAME)) || target != expect;
        }
        if (unknown || stale) {
            // 상태를 모르거나 위젯이 낡은 칸은 스위치를 누르지 않는다. 화면만 열어 서비스가 캐시를 채우게 한다(MainActivity.readState 와 같은 경로).
            if (stale) SimTapWidget.refresh(this);
            SimTapService.cancel(this);
            try {
                startActivity(simManagerIntent());
                Toast.makeText(this, R.string.toast_read_first, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.toast_open_fail, Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(ARMED_AT, SystemClock.elapsedRealtime())
                .putInt(ARM_WIDGET, getIntent().getIntExtra(EXTRA_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID))
                .putInt(ARM_SLOT, slot)
                .putInt(ARM_TARGET, target)
                .putString(ARM_NAME, name).commit();
        try {
            startActivity(simManagerIntent());
            SimTapService.onArmed(slot);
        } catch (ActivityNotFoundException | SecurityException e) {
            SimTapService.disarm(this);
            Toast.makeText(this, R.string.toast_open_fail, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /**
     * 인터넷 연결 패널(API 29+). 두 칸 모두 이 창을 쓴다. ACTION_WIFI 패널은 설정 앱 화면으로 뜨고 스위치에 id 가 없다
     * (Fold8 One UI 9.0 실기기). 그 아래 버전이거나 패널이 없으면 해당 설정 화면. 둘 다 없으면 토스트.
     * 인터넷 연결 패널을 열었을 때만 true.
     */
    private boolean openPanel(boolean wifi) {
        String panel = Build.VERSION.SDK_INT < 29 ? null : Settings.Panel.ACTION_INTERNET_CONNECTIVITY;
        String[] actions = { panel, wifi ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_DATA_USAGE_SETTINGS };
        for (String a : actions) {
            if (a == null) continue;
            try {
                startActivity(new Intent(a));
                return a == panel;
            } catch (ActivityNotFoundException | SecurityException e) {
                // 다음 후보로 넘어간다.
            }
        }
        Toast.makeText(this, R.string.toast_no_app, Toast.LENGTH_LONG).show();
        return false;
    }
}
