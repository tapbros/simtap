package com.tapbros.simtap;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

/** 위젯 칸 탭을 받아 arm(위젯 id, 칸, 시각)을 기록하고 SIM 관리자 화면을 연 뒤 바로 끝난다. */
public class TrampolineActivity extends Activity {
    static final String PREFS = "arm";
    static final String ARMED_AT = "armedAt";
    static final String ARM_WIDGET = "armWidget";
    static final String ARM_SLOT = "armSlot";
    /** 회선 칸의 목표값: 칸이 보이던 상태의 반대(1 켜기, 0 끄기), 모르면 -1(토글). */
    static final String ARM_TARGET = "armTarget";
    static final String EXTRA_WIDGET = "widget";
    static final String EXTRA_SLOT = "slot";
    static final String EXTRA_TARGET = "target";
    /**
     * SimCardMgrActivity 본체와 OPEN_SIMCARD_ACTIVITY 는 MODIFY_PHONE_STATE 를 요구해 일반 앱이 못 연다.
     * 같은 화면을 가리키는 activity-alias NoPermissionSimCardMgrActivity 는 권한 속성이 없다(telephonyui 매니페스트).
     */
    static final String SIM_MANAGER_ACTION = "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS";
    static final String TUI_PKG = "com.samsung.android.app.telephonyui";
    static final String SIM_MANAGER_ALIAS = TUI_PKG + ".netsettings.NoPermissionSimCardMgrActivity";

    /** SIM 관리자 화면 Intent. arm 하지 않으므로 여는 것만으로는 아무것도 누르지 않는다. */
    static Intent simManagerIntent() {
        return new Intent(SIM_MANAGER_ACTION).setClassName(TUI_PKG, SIM_MANAGER_ALIAS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int slot = getIntent().getIntExtra(EXTRA_SLOT, -1);
        if (slot < 0 || slot > SimTapWidget.SLOT_DATA) { finish(); return; }
        if (!SimTapService.isRunning()) {
            Toast.makeText(this, R.string.toast_service_off, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(ARMED_AT, SystemClock.elapsedRealtime())
                .putInt(ARM_WIDGET, getIntent().getIntExtra(EXTRA_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID))
                .putInt(ARM_SLOT, slot)
                .putInt(ARM_TARGET, getIntent().getIntExtra(EXTRA_TARGET, -1)).commit();
        try {
            startActivity(simManagerIntent());
            SimTapService.onArmed();
        } catch (ActivityNotFoundException | SecurityException e) {
            SimTapService.disarm(this);
            Toast.makeText(this, R.string.toast_open_fail, Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
