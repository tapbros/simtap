package com.tapbros.simtap;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * SIM 관리자 화면에서 접근성 서비스가 마지막으로 본 값의 캐시. 일반 앱은 SIM 회선 상태를 직접 바꾸거나
 * 이름을 정할 수 없으므로 위젯은 이 캐시만 그린다. 회선은 화면 위에서부터의 순서(0 = SIM 1 칸)로 저장한다.
 */
final class SimCache {
    private static final String PREFS = "sim";
    /** 본 적 있는 on_off_switch 수. 0 이면 아직 화면을 본 적이 없다. */
    private static final String LINES = "lines";
    private static final String DATA_SHOWN = "dataShown";
    private static final String DATA_NAME = "dataName";

    private SimCache() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int lineCount(Context ctx) { return prefs(ctx).getInt(LINES, 0); }

    /** 캐시한 SIM 이름. 없으면 null. */
    static String name(Context ctx, int i) { return prefs(ctx).getString("name_" + i, null); }

    /** 1 켜짐, 0 꺼짐, -1 모름. */
    static int on(Context ctx, int i) { return prefs(ctx).getInt("on_" + i, -1); }

    static boolean dataShown(Context ctx) { return prefs(ctx).getBoolean(DATA_SHOWN, false); }

    static String dataName(Context ctx) { return prefs(ctx).getString(DATA_NAME, ""); }

    /**
     * 맨 위에서 본 스위치들을 저장한다. complete 가 false 면(목록 아래가 화면 밖) 본 수가 적어도 줄이지 않는다.
     * 값이 하나라도 바뀌었으면 true.
     */
    static boolean saveLines(Context ctx, List<String> names, List<Boolean> on, boolean complete) {
        SharedPreferences p = prefs(ctx);
        int old = p.getInt(LINES, 0);
        int seen = names.size();
        int count = complete ? seen : Math.max(old, seen);
        boolean changed = count != old;
        SharedPreferences.Editor e = p.edit().putInt(LINES, count);
        for (int i = 0; i < seen; i++) {
            int v = on.get(i) ? 1 : 0;
            if (!names.get(i).equals(p.getString("name_" + i, null)) || v != p.getInt("on_" + i, -1)) changed = true;
            e.putString("name_" + i, names.get(i)).putInt("on_" + i, v);
        }
        for (int i = count; i < old; i++) e.remove("name_" + i).remove("on_" + i);
        if (changed) e.commit();
        return changed;
    }

    static boolean saveData(Context ctx, boolean shown, String name) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean(DATA_SHOWN, false) == shown && name.equals(p.getString(DATA_NAME, ""))) return false;
        p.edit().putBoolean(DATA_SHOWN, shown).putString(DATA_NAME, name).commit();
        return true;
    }
}
