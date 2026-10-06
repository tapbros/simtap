package com.tapbros.simtap;

import android.content.Context;
import android.provider.Settings;

/**
 * 5x1 위젯의 와이파이·데이터 칸이 보이는 켜짐 여부. 권한 없이 Settings.Global 값을 읽는다.
 * "mobile_data" 는 공개 상수가 아니라 기종이나 버전에 따라 읽기가 막힐 수 있다. 그때는 모름(-1)이고 칸은 「열기」로 그린다.
 */
final class NetState {
    static final String WIFI = "wifi_on";
    static final String MOBILE = "mobile_data";
    /** SimTapService 가 지켜보는 키. */
    static final String[] KEYS = { WIFI, MOBILE };

    private NetState() {}

    /** 1 켜짐, 0 꺼짐, -1 모름. wifi_on 은 0 이 꺼짐이고 그 밖의 양수(비행기 모드 중 켬 등)는 켜짐이다. */
    static int read(Context ctx, String key) {
        try {
            int v = Settings.Global.getInt(ctx.getContentResolver(), key, -1);
            return v < 0 ? -1 : v > 0 ? 1 : 0;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
