package com.tapbros.simtap;

import android.content.Context;

// Boundary fakes only. Service, cache and network decision code are compiled from src/.
final class TargetConfig {
    static final String PKG = "com.samsung.android.app.telephonyui";
}
final class SimTapWidget {
    static final int SLOT_DATA = 2, SLOT_WIFI = 3, SLOT_MOBILE = 4;
    static void refresh(Context context) {}
}
final class TrampolineActivity {
    static final String PREFS = "arm", ARMED_AT = "armedAt", ARM_WIDGET = "armWidget",
            ARM_SLOT = "armSlot", ARM_TARGET = "armTarget", ARM_NAME = "armName";
}
final class NetState {
    static final String WIFI = "wifi_on", MOBILE = "mobile_data";
    static final String[] KEYS = { WIFI, MOBILE };
    static int wifi, mobile;
    static int read(Context context, String key) { return key.equals(WIFI) ? wifi : mobile; }
}
final class R {
    static final class string {
        static final int sim_default = 1, toast_data_missing = 2, toast_line_missing = 3;
    }
}
