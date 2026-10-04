package com.tapbros.fakesim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 삼성 SIM 관리자 첫 화면 흉내(One UI 9.0 KR 디컴파일 기준). 진짜 SIM 은 건드리지 않는다.
 * 시나리오: am start -n com.tapbros.fakesim/.SimCardMgrActivity --es scenario psim_esim [--es rename "old=new"]
 */
public class SimCardMgrActivity extends Activity {
    static final String EXTRA_SCENARIO = "scenario";
    static final String EXTRA_RENAME = "rename";
    static final long DELAY_MS = 2000;
    static final long DELAY_ESIM_MS = 6000;

    private static final int T_HEADER = 0, T_LINE = 1, T_ADD = 2, T_PREF = 3, T_SWITCH = 4;

    private static final class Row {
        final int type;
        final String text;
        final SimState.Line line;
        Row(int type, String text, SimState.Line line) { this.type = type; this.text = text; this.line = line; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private SimState state;
    private Adapter adapter;
    /** 확인 창이 떠 있거나 회선 작업 중이다. 그동안 다른 스위치 탭은 창을 새로 띄우지 않는다. */
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_sim_mgr);
        final ScrollView outer = findViewById(R.id.outer_scroll);
        final ListView list = findViewById(R.id.recycler_view);
        // ScrollView 안의 ListView 는 높이를 정해 줘야 한다. 바깥 층 높이로 맞춰 큰 제목만 바깥 층이 밀어 올린다.
        outer.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (outer.getHeight() <= 0) return;
                outer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                ViewGroup.LayoutParams lp = list.getLayoutParams();
                lp.height = outer.getHeight();
                list.setLayoutParams(lp);
            }
        });
        adapter = new Adapter();
        list.setAdapter(adapter);
        if (b == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) reload();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void handleIntent(Intent i) {
        String sc = i.getStringExtra(EXTRA_SCENARIO);
        String rename = i.getStringExtra(EXTRA_RENAME);
        if (sc != null) {
            SimState s = SimState.scenario(sc);
            if (s == null) {
                Log.i(SimState.TAG, "unknown scenario " + sc);
                Toast.makeText(this, getString(R.string.toast_bad_scenario, sc), Toast.LENGTH_LONG).show();
            } else {
                s.save(this);
                Log.i(SimState.TAG, "scenario " + sc);
                Toast.makeText(this, getString(R.string.toast_scenario, sc), Toast.LENGTH_SHORT).show();
            }
        }
        if (rename != null) {
            SimState s = SimState.load(this);
            if (s.rename(rename)) s.save(this);
            else Toast.makeText(this, getString(R.string.toast_bad_rename, rename), Toast.LENGTH_LONG).show();
        }
        // 같은 extra 가 재생성 때 다시 적용되지 않게 지운다.
        i.removeExtra(EXTRA_SCENARIO);
        i.removeExtra(EXTRA_RENAME);
        if (adapter != null && (sc != null || rename != null)) reload();
    }

    private void reload() {
        state = SimState.load(this);
        rows.clear();
        rows.add(new Row(T_HEADER, getString(R.string.hdr_sim), null));
        for (SimState.Line l : state.lines) if (!l.esim) rows.add(new Row(T_LINE, null, l));
        rows.add(new Row(T_HEADER, getString(R.string.hdr_esim), null));
        for (SimState.Line l : state.lines) if (l.esim) rows.add(new Row(T_LINE, null, l));
        rows.add(new Row(T_ADD, getString(R.string.add_esim), null));
        rows.add(new Row(T_HEADER, getString(R.string.hdr_primary), null));
        rows.add(new Row(T_PREF, getString(R.string.pref_call), null));
        rows.add(new Row(T_PREF, getString(R.string.pref_msg), null));
        rows.add(new Row(T_PREF, getString(R.string.pref_data), null));
        rows.add(new Row(T_SWITCH, getString(R.string.pref_data_switch), null));
        adapter.notifyDataSetChanged();
    }

    private boolean dataRowEnabled() { return state.onCount() >= 2; }

    private SimState.Line firstOtherOn(SimState.Line l) {
        for (SimState.Line o : state.lines) if (o != l && o.present && o.on) return o;
        return null;
    }

    /** 스위치 영역 탭: 스위치는 바로 원래 값으로 되돌리고 확인 창을 띄운다. 값은 확인 뒤에만 바뀐다. */
    private void onSwitchTap(SimState.Line l, Switch sw) {
        sw.setChecked(!l.on);
        sw.setChecked(l.on);
        if (busy) return;
        if (l.on) showOff(l);
        else showOn(l);
    }

    private void showOff(final SimState.Line l) {
        final SimState.Line other = firstOtherOn(l);
        boolean isData = state.dataSim >= 0 && state.lines.get(state.dataSim) == l;
        String msg = null;
        if (other == null) msg = getString(R.string.dlg_off_last, l.name);
        else if (isData) msg = getString(R.string.dlg_off_data, l.name, other.name);
        dialog(getString(R.string.dlg_off_title, l.name), msg,
                getString(R.string.btn_off), () -> state.set(l, false),
                getString(R.string.btn_cancel), null, null, null, delayOf(l));
    }

    private void showOn(final SimState.Line l) {
        if (state.onCount() >= 2) { showMax(l); return; }
        if (state.dangerOn) {
            // 꺼질 회선: 켜진 다른 회선. 본문 자리 두 개를 못 채우면 「다른 SIM」으로 채운다(본문 문형은 실기기 문자열, 채우는 규칙은 가정).
            final List<SimState.Line> off = new ArrayList<>();
            for (SimState.Line o : state.lines) if (o != l && o.present && o.on) off.add(o);
            String a = off.size() > 0 ? off.get(0).name : getString(R.string.danger_pad);
            String b = off.size() > 1 ? off.get(1).name : getString(R.string.danger_pad);
            dialog(getString(R.string.dlg_on_title, l.name), getString(R.string.dlg_on_danger, a, b),
                    getString(R.string.btn_on), () -> {
                        for (SimState.Line o : off) state.set(o, false);
                        state.set(l, true);
                    },
                    getString(R.string.btn_cancel), null, null, null, delayOf(l));
            return;
        }
        dialog(getString(R.string.dlg_on_title, l.name), null,
                getString(R.string.btn_on), () -> state.set(l, true),
                getString(R.string.btn_cancel), null, null, null, delayOf(l));
    }

    /** 이미 2개가 켜져 있다. 버튼 3개: neutral 「A 끄기」, negative 「B 끄기」, positive 「취소」. 고른 회선을 끄고 대상을 켠다. */
    private void showMax(final SimState.Line l) {
        final List<SimState.Line> on = new ArrayList<>();
        for (SimState.Line o : state.lines) if (o.present && o.on) on.add(o);
        final SimState.Line a = on.get(0), b = on.get(1);
        dialog(getString(R.string.dlg_max_title), getString(R.string.dlg_max_body, l.name),
                getString(R.string.btn_cancel), null,
                getString(R.string.btn_off_fmt, b.name), () -> { state.set(b, false); state.set(l, true); },
                getString(R.string.btn_off_fmt, a.name), () -> { state.set(a, false); state.set(l, true); },
                delayOf(l));
    }

    private static long delayOf(SimState.Line l) { return l.esim ? DELAY_ESIM_MS : DELAY_MS; }

    /**
     * 플랫폼 AlertDialog(button1 positive, button2 negative, button3 neutral). 동작이 null 인 버튼은 바꾸지 않고 닫는다.
     * 동작이 있는 버튼은 창을 닫지 않고 버튼을 끄고 ProgressBar 를 보인 채 지연 뒤 상태를 바꾸고 닫는다.
     */
    private void dialog(String title, String msg, String pos, Runnable posRun, String neg, Runnable negRun,
                        String neu, Runnable neuRun, final long delay) {
        View v = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        final ProgressBar progress = v.findViewById(R.id.progress);
        AlertDialog.Builder bld = new AlertDialog.Builder(this).setTitle(title).setView(v);
        if (msg != null) bld.setMessage(msg);
        bld.setPositiveButton(pos, null);
        if (neg != null) bld.setNegativeButton(neg, null);
        if (neu != null) bld.setNeutralButton(neu, null);
        final AlertDialog d = bld.create();
        d.setOnDismissListener(x -> { if (!working) busy = false; });
        busy = true;
        working = false;
        d.show();
        wire(d, AlertDialog.BUTTON_POSITIVE, posRun, progress, delay);
        wire(d, AlertDialog.BUTTON_NEGATIVE, negRun, progress, delay);
        wire(d, AlertDialog.BUTTON_NEUTRAL, neuRun, progress, delay);
    }

    /** 확인 뒤 지연 중이다. 그동안 창이 닫혀도(뒤로 가기 등) busy 를 풀지 않는다. */
    private boolean working;

    private void wire(final AlertDialog d, int which, final Runnable run, final ProgressBar progress, final long delay) {
        Button btn = d.getButton(which);
        if (btn == null || btn.getVisibility() != View.VISIBLE) return;
        btn.setOnClickListener(x -> {
            if (run == null) { d.dismiss(); return; }
            working = true;
            for (int w : new int[] { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL }) {
                Button o = d.getButton(w);
                if (o != null) o.setEnabled(false);
            }
            progress.setVisibility(View.VISIBLE);
            d.setCancelable(false);
            handler.postDelayed(() -> {
                run.run();
                state.fixData();
                state.save(this);
                working = false;
                busy = false;
                if (!isDestroyed()) {
                    if (d.isShowing()) d.dismiss();
                    reload();
                }
            }, delay);
        });
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_SHORT).show(); }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 5; }
        @Override public int getItemViewType(int p) { return rows.get(p).type; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override public boolean isEnabled(int p) {
            Row r = rows.get(p);
            if (r.type == T_HEADER) return false;
            if (r.type == T_PREF && r.text.equals(getString(R.string.pref_data))) return dataRowEnabled();
            return true;
        }

        @Override public View getView(int p, View cv, ViewGroup parent) {
            Row r = rows.get(p);
            switch (r.type) {
                case T_HEADER: {
                    TextView t = (TextView) (cv != null ? cv : getLayoutInflater().inflate(R.layout.row_header, parent, false));
                    t.setText(r.text);
                    return t;
                }
                case T_LINE: return lineView(r.line, cv, parent);
                case T_ADD: {
                    View v = cv != null ? cv : getLayoutInflater().inflate(R.layout.row_pref, parent, false);
                    ((TextView) v.findViewById(android.R.id.title)).setText(r.text);
                    v.findViewById(android.R.id.summary).setVisibility(View.GONE);
                    v.setOnClickListener(x -> toast(R.string.add_esim));
                    return v;
                }
                default: return prefView(r, cv, parent);
            }
        }

        private View lineView(final SimState.Line l, View cv, ViewGroup parent) {
            View v = cv != null ? cv : getLayoutInflater().inflate(R.layout.row_line, parent, false);
            TextView name = v.findViewById(R.id.line_name);
            TextView carrier = v.findViewById(R.id.line_carrier);
            View swLayout = v.findViewById(R.id.on_off_switch_layout);
            final Switch sw = v.findViewById(R.id.on_off_switch);
            if (!l.present) {
                name.setText(R.string.no_sim);
                carrier.setVisibility(View.GONE);
                swLayout.setVisibility(View.GONE);
                v.setOnClickListener(null);
                v.setClickable(false);
                return v;
            }
            name.setText(l.name);
            carrier.setText(l.carrier);
            carrier.setVisibility(View.VISIBLE);
            swLayout.setVisibility(View.VISIBLE);
            sw.setContentDescription(l.name);
            sw.setChecked(l.on);
            // 본문 탭은 상세 화면으로 가는 자리다. 토글하지 않는다.
            v.setOnClickListener(x -> toast(R.string.toast_detail));
            swLayout.setOnClickListener(x -> onSwitchTap(l, sw));
            return v;
        }

        private View prefView(Row r, View cv, ViewGroup parent) {
            View v = cv != null ? cv : getLayoutInflater().inflate(R.layout.row_pref, parent, false);
            TextView title = v.findViewById(android.R.id.title);
            TextView summary = v.findViewById(android.R.id.summary);
            final Switch sw = v.findViewById(R.id.pref_switch);
            title.setText(r.text);
            summary.setVisibility(View.VISIBLE);
            boolean enabled = true;
            if (r.type == T_SWITCH) {
                summary.setText(R.string.pref_data_switch_summary);
                sw.setVisibility(View.VISIBLE);
                sw.setChecked(state.dataSwitch);
                v.setOnClickListener(x -> {
                    state.dataSwitch = !state.dataSwitch;
                    Log.i(SimState.TAG, "dataSwitch " + (state.dataSwitch ? 1 : 0));
                    state.save(SimCardMgrActivity.this);
                    sw.setChecked(state.dataSwitch);
                });
            } else {
                sw.setVisibility(View.GONE);
                boolean data = r.text.equals(getString(R.string.pref_data));
                String s = state.dataName();
                if (!data) {
                    SimState.Line first = firstOtherOn(null);
                    s = first != null ? first.name : "";
                }
                summary.setText(s.isEmpty() ? getString(R.string.pref_none) : s);
                if (data) {
                    // 두 회선이 모두 켜져 있을 때만 고를 수 있다. ViewGroup.setEnabled 는 자식에 번지지 않아 제목과 요약도 직접 끈다.
                    enabled = dataRowEnabled();
                    v.setOnClickListener(x -> {
                        if (dataRowEnabled() && !busy) startActivity(new Intent(SimCardMgrActivity.this, DataSimPickActivity.class));
                    });
                } else {
                    v.setOnClickListener(x -> toast(R.string.toast_detail));
                }
            }
            v.setEnabled(enabled);
            title.setEnabled(enabled);
            summary.setEnabled(enabled);
            return v;
        }
    }
}
