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
    /** 「모바일 데이터」 행을 본 적이 있다. 그 행이 지금 enabled 인지는 저장하지 않는다(회선을 바꾼 직후에는 잠시 disabled 다). */
    private static final String DATA_PRESENT = "dataPresent";
    /** v0.01.00.27 까지의 키(행이 있고 enabled 였다). DATA_PRESENT 가 아직 없을 때만 읽는다. */
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

    /** 캐시한 회선 가운데 켜진 수. */
    static int onCount(Context ctx) {
        int n = 0;
        for (int i = 0; i < lineCount(ctx); i++) if (on(ctx, i) == 1) n++;
        return n;
    }

    static boolean dataPresent(Context ctx) { return dataPresent(prefs(ctx)); }

    private static boolean dataPresent(SharedPreferences p) {
        return p.contains(DATA_PRESENT) ? p.getBoolean(DATA_PRESENT, false) : p.getBoolean(DATA_SHOWN, false);
    }

    /** 위젯이 데이터 칸을 보이는 조건. 단일 SIM 에도 데이터 행은 있으므로 회선 수도 본다. */
    static boolean dataCell(Context ctx) { return dataPresent(ctx) && lineCount(ctx) >= 2; }

    static String dataName(Context ctx) { return prefs(ctx).getString(DATA_NAME, ""); }

    /**
     * 맨 위에서 본 스위치들을 저장한다. complete 가 false 면(데이터 행이 안 보이고 목록 아래가 화면 밖) 본 수가 적어도 줄이지 않는다.
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

    /**
     * present: 데이터 행이 화면에 있다(disabled 여도 true). name: 그 행의 요약(데이터 SIM 이름).
     * 행이 있는데 요약이 비어 있으면(전환 중) 기존 이름을 둔다. 값이 바뀌었으면 true.
     */
    static boolean saveData(Context ctx, boolean present, String name) {
        SharedPreferences p = prefs(ctx);
        String old = p.getString(DATA_NAME, "");
        if (!present) name = "";
        else if (name.trim().isEmpty()) name = old;
        boolean changed = dataPresent(p) != present || !name.equals(old);
        // 값이 같아도 옛 키만 있으면 새 키로 한 번 옮겨 쓴다.
        if (!changed && p.contains(DATA_PRESENT)) return false;
        p.edit().putBoolean(DATA_PRESENT, present).remove(DATA_SHOWN).putString(DATA_NAME, name).commit();
        return changed;
    }
}
