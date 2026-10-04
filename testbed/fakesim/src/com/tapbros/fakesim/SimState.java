package com.tapbros.fakesim;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 가짜 회선 상태. SharedPreferences 에 JSON 으로 둔다. 화면 순서는 물리 SIM(슬롯 순) 다음 eSIM(subId 순)이다. */
final class SimState {
    static final String TAG = "FakeSim";
    private static final String PREFS = "fakesim";
    private static final String KEY = "state";
    static final String DEFAULT_SCENARIO = "psim_esim";

    static final class Line {
        String name;
        String carrier;
        boolean esim;
        boolean on;
        /** false 면 「SIM 카드 없음」 자리(유심 없음). 회선으로 세지 않는다. */
        boolean present = true;
        int subId;
    }

    final List<Line> lines = new ArrayList<>();
    /** 데이터 SIM 의 lines 위치. -1 이면 없음. 이름이 같은 회선(same_name)이 있어 이름이 아니라 위치로 둔다. */
    int dataSim = -1;
    /** 켜기 창을 본문 있는 위험 창으로 띄운다(danger_on). */
    boolean dangerOn;
    boolean dataSwitch;

    private static Line line(String name, String carrier, boolean esim, boolean on, int subId) {
        Line l = new Line();
        l.name = name;
        l.carrier = carrier;
        l.esim = esim;
        l.on = on;
        l.subId = subId;
        return l;
    }

    private static Line absent() {
        Line l = line("", "", false, false, 0);
        l.present = false;
        return l;
    }

    /** 이름의 시나리오 초기 상태. 모르는 이름이면 null. */
    static SimState scenario(String name) {
        SimState s = new SimState();
        switch (name) {
            case "psim_esim":
                s.lines.add(line("SKT", "SKT", false, true, 1));
                s.lines.add(line("KT eSIM", "KT", true, true, 2));
                break;
            case "psim_esim2":
                s.lines.add(line("SKT", "SKT", false, true, 1));
                s.lines.add(line("KT eSIM", "KT", true, true, 3));
                s.lines.add(line("Travel", "Travel", true, false, 2));
                break;
            case "esim_only":
                s.lines.add(absent());
                s.lines.add(line("LG U+", "LG U+", true, true, 1));
                s.lines.add(line("KT eSIM", "KT", true, true, 2));
                break;
            case "same_name":
                s.lines.add(line("SIM", "SKT", false, true, 1));
                s.lines.add(line("SIM", "KT", true, true, 2));
                break;
            case "prefix_name":
                s.lines.add(line("SKT", "SKT", false, false, 1));
                s.lines.add(line("SKT eSIM", "SKT", true, false, 2));
                break;
            case "danger_on":
                s.lines.add(line("SKT", "SKT", false, false, 1));
                s.lines.add(line("KT eSIM", "KT", true, true, 2));
                s.dangerOn = true;
                break;
            case "max_on":
                s.lines.add(line("SKT", "SKT", false, true, 1));
                s.lines.add(line("KT eSIM", "KT", true, true, 2));
                s.lines.add(line("Travel", "Travel", true, false, 3));
                break;
            case "psim2":
                s.lines.add(line("SKT", "SKT", false, true, 1));
                s.lines.add(line("KT", "KT", false, true, 2));
                break;
            default:
                return null;
        }
        s.sort();
        s.fixData();
        return s;
    }

    /** 물리 SIM 은 넣은 순서(슬롯), eSIM 은 subId 순. */
    private void sort() {
        List<Line> p = new ArrayList<>(), e = new ArrayList<>();
        for (Line l : lines) (l.esim ? e : p).add(l);
        Collections.sort(e, (a, b) -> Integer.compare(a.subId, b.subId));
        lines.clear();
        lines.addAll(p);
        lines.addAll(e);
    }

    /** 데이터 SIM 이 꺼졌거나 없으면 켜진 첫 회선으로 옮긴다. 켜진 회선이 없으면 그대로 둔다. */
    void fixData() {
        if (dataSim >= 0 && dataSim < lines.size() && lines.get(dataSim).on) return;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).present && lines.get(i).on) { setData(i); return; }
        }
        if (dataSim < 0 || dataSim >= lines.size()) {
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).present) { dataSim = i; return; }
        }
    }

    void setData(int i) {
        if (i == dataSim) return;
        Log.i(TAG, "data " + dataName() + "->" + (i >= 0 ? lines.get(i).name : ""));
        dataSim = i;
    }

    String dataName() {
        return dataSim >= 0 && dataSim < lines.size() ? lines.get(dataSim).name : "";
    }

    int onCount() {
        int n = 0;
        for (Line l : lines) if (l.present && l.on) n++;
        return n;
    }

    void set(Line l, boolean on) {
        if (l.on == on) return;
        Log.i(TAG, "toggle " + l.name + " " + (l.on ? 1 : 0) + "->" + (on ? 1 : 0));
        l.on = on;
    }

    /** "old=new" 의 첫 old 회선 이름을 바꾼다. 못 바꾸면 false. */
    boolean rename(String spec) {
        int eq = spec.indexOf('=');
        if (eq <= 0 || eq == spec.length() - 1) return false;
        String from = spec.substring(0, eq), to = spec.substring(eq + 1);
        for (Line l : lines) {
            if (l.present && l.name.equals(from)) {
                Log.i(TAG, "rename " + from + "->" + to);
                l.name = to;
                return true;
            }
        }
        return false;
    }

    static SimState load(Context ctx) {
        String json = prefs(ctx).getString(KEY, null);
        if (json != null) {
            try {
                JSONObject o = new JSONObject(json);
                SimState s = new SimState();
                s.dataSim = o.getInt("dataSim");
                s.dangerOn = o.getBoolean("dangerOn");
                s.dataSwitch = o.getBoolean("dataSwitch");
                JSONArray a = o.getJSONArray("lines");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    Line l = line(j.getString("name"), j.getString("carrier"), j.getBoolean("esim"),
                            j.getBoolean("on"), j.getInt("subId"));
                    l.present = j.getBoolean("present");
                    s.lines.add(l);
                }
                return s;
            } catch (JSONException e) {
                Log.w(TAG, "bad saved state, reset", e);
            }
        }
        SimState s = scenario(DEFAULT_SCENARIO);
        s.save(ctx);
        return s;
    }

    void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("dataSim", dataSim);
            o.put("dangerOn", dangerOn);
            o.put("dataSwitch", dataSwitch);
            JSONArray a = new JSONArray();
            for (Line l : lines) {
                JSONObject j = new JSONObject();
                j.put("name", l.name);
                j.put("carrier", l.carrier);
                j.put("esim", l.esim);
                j.put("on", l.on);
                j.put("present", l.present);
                j.put("subId", l.subId);
                a.put(j);
            }
            o.put("lines", a);
            prefs(ctx).edit().putString(KEY, o.toString()).commit();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
