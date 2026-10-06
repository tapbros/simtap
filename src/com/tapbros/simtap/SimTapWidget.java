package com.tapbros.simtap;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

/**
 * 4x1 패널. 칸 0 = SIM 1 회선, 칸 1 = SIM 2 회선, 칸 2 = 데이터 SIM.
 * 서비스가 화면에서 그 행을 본 적이 있어야 칸을 보인다. 캐시가 없으면 SIM 1 칸만 「눌러서 읽기」로 보인다.
 * 데이터 칸은 회선이 2개 이상일 때 보이고 켜진 회선이 2개 미만이면 흐리게 「SIM 2개 필요」로 그린다.
 */
public class SimTapWidget extends AppWidgetProvider {
    static final int SLOT_DATA = 2;
    private static final int[] CELL = { R.id.cell0, R.id.cell1, R.id.cell2 };
    private static final int[] NAME = { R.id.name0, R.id.name1, R.id.name2 };
    private static final int[] STATE = { R.id.state0, R.id.state1, R.id.state2 };

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 앱을 덮어 설치하면 런처가 위젯을 초기 레이아웃으로 되돌려 탭이 앱 실행으로 바뀐다(v0.01.00.02 실기기). 다시 그린다.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            // 런처가 업데이트 직후 위젯을 다시 불러오며 초기 레이아웃으로 덮을 수 있어 몇 초 뒤 한 번 더 그린다.
            Log.i("SimTap", "package replaced, refresh");
            refresh(ctx);
            final PendingResult pr = goAsync();
            final Context app = ctx.getApplicationContext();
            new Handler(Looper.getMainLooper()).postDelayed(() -> { refresh(app); pr.finish(); }, 4000);
        } else if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) refresh(ctx);
        else super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, id));
    }

    /**
     * 접힌 상태에서 한 갱신이 펼친 화면 위젯에 반영되지 않았다(Fold8 One UI 9.0 실기기). 런처가 크기 정보를 다시
     * 알려 줄 때 그 위젯을 다시 그린다.
     */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, android.os.Bundle opts) {
        Log.i("SimTap", "options changed widget=" + id);
        mgr.updateAppWidget(id, build(ctx, id));
    }

    /** 위젯을 지우면 그 위젯 id 로 남은 arm 기록을 지운다. 위젯별로 저장하는 다른 값은 없다. */
    @Override
    public void onDeleted(Context ctx, int[] ids) {
        SimTapService.forgetWidgets(ctx, ids);
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        for (int id : mgr.getAppWidgetIds(new ComponentName(ctx, SimTapWidget.class))) {
            mgr.updateAppWidget(id, build(ctx, id));
        }
    }

    private static RemoteViews build(Context ctx, int id) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        int lines = SimCache.lineCount(ctx);
        for (int slot = 0; slot < SLOT_DATA; slot++) {
            boolean show = slot == 0 || lines > slot;
            rv.setViewVisibility(CELL[slot], show ? View.VISIBLE : View.GONE);
            if (!show) continue;
            String name = lines > slot ? SimCache.name(ctx, slot) : null;
            if (name == null || name.isEmpty()) name = ctx.getString(R.string.sim_default, slot + 1);
            int on = lines > slot ? SimCache.on(ctx, slot) : -1;
            String state = ctx.getString(on == 1 ? R.string.state_on : on == 0 ? R.string.state_off : R.string.state_tap_to_read);
            int bg = on == 1 ? R.drawable.bg_on : on == 0 ? R.drawable.bg_off : R.drawable.bg_unknown;
            // 보인 상태의 반대가 목표다. 서비스는 실제 값이 이미 목표면 누르지 않는다.
            fill(ctx, rv, id, slot, name, state, bg, on == 1 ? 0 : on == 0 ? 1 : -1, name);
        }
        boolean data = SimCache.dataCell(ctx);
        rv.setViewVisibility(CELL[SLOT_DATA], data ? View.VISIBLE : View.GONE);
        if (data) {
            // 데이터 행이 enabled 인지는 캐시하지 않는다. 캐시한 회선의 켜짐 수로 판정한다(SimTapService.cache 주석).
            // 흐린 칸도 누르면 화면을 열고 seekData 가 행이 실제로 눌리는지 본다.
            boolean ready = SimCache.onCount(ctx) >= 2;
            String cur = SimCache.dataName(ctx);
            fill(ctx, rv, id, SLOT_DATA, ctx.getString(R.string.data_label),
                    !ready ? ctx.getString(R.string.state_need_two) : cur.isEmpty() ? ctx.getString(R.string.state_unknown) : cur,
                    ready ? R.drawable.bg_data : R.drawable.bg_unknown, -1, null);
        }
        return rv;
    }

    /** lineName: 회선 칸이 보인 SIM 이름(트램펄린이 캐시와 대조해 낡은 위젯을 거른다). 데이터 칸은 null. */
    private static void fill(Context ctx, RemoteViews rv, int id, int slot, String name, String state, int bg, int target,
                             String lineName) {
        rv.setTextViewText(NAME[slot], name);
        rv.setTextViewText(STATE[slot], state);
        rv.setInt(CELL[slot], "setBackgroundResource", bg);
        rv.setContentDescription(CELL[slot], ctx.getString(R.string.desc_fmt, name, state));
        // 칸마다 requestCode 와 data 를 달리해 extra 만 다른 Intent 가 하나로 합쳐지지 않게 한다. data 에 target 도 넣어
        // 런처에 남은 낡은 RemoteViews 가 자기 표시 상태의 target 을 보내게 한다(FLAG_UPDATE_CURRENT 가 덮지 않는다).
        // 실제 값이 이미 그 target 이면 서비스의 「already target」 경로가 누르지 않는다. 이름도 같은 이유로 넣는다(해시로 줄인다).
        String nameKey = lineName != null ? Integer.toHexString(lineName.hashCode()) : "-";
        Intent i = new Intent(ctx, TrampolineActivity.class)
                .setData(Uri.parse("simtap://widget/" + id + "/" + slot + "/" + target + "/" + nameKey))
                .putExtra(TrampolineActivity.EXTRA_NAME, lineName)
                .putExtra(TrampolineActivity.EXTRA_WIDGET, id)
                .putExtra(TrampolineActivity.EXTRA_SLOT, slot)
                .putExtra(TrampolineActivity.EXTRA_TARGET, target);
        rv.setOnClickPendingIntent(CELL[slot], PendingIntent.getActivity(ctx, id * 10 + slot, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
}
