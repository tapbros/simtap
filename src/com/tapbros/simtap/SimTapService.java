package com.tapbros.simtap;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SIM 관리자 화면(telephonyui)만 본다.
 * 1) 화면을 볼 때마다(arm 여부와 무관) 회선 스위치의 순서·이름·값과 데이터 행을 캐시하고 위젯을 갱신한다.
 * 2) 위젯 칸이 arm 한 뒤에만 그 칸의 행을 한 번 누른다. arm 은 첫 이벤트에서 메모리 작업으로 옮기고 바로 지우므로
 *    스크롤이나 클릭이 다시 부르는 이벤트가 같은 arm 으로 또 누르지 않는다.
 */
public class SimTapService extends AccessibilityService {
    static final String TAG = "SimTap";
    static final String PKG = "com.samsung.android.app.telephonyui";
    static final String SWITCH_ID = PKG + ":id/on_off_switch";
    static final String TITLE_ID = PKG + ":id/title";
    static final String SUMMARY_ID = PKG + ":id/summary";
    static final String BUTTON_OK = "android:id/button1";
    static final String BUTTON_CANCEL = "android:id/button2";
    static final String SIM_MGR_CLASS_SUFFIX = "SimCardMgrActivity";
    /** arm 뒤 이 안에 SIM 관리자 이벤트가 와야 작업을 시작한다. */
    static final long ARM_WINDOW_MS = 5000;
    /** arm 뒤 이 안에 행을 누르지 못하면 토스트로 알린다. 스크롤 시간을 포함한다. */
    static final long SEEK_MS = 8000;
    /** 행을 누른 뒤 값 변화를 기다리는 시간. 끄기 확인은 사용자가 누르므로 넉넉히 둔다. */
    static final long OBSERVE_MS = 30000;
    static final long SETTLE_MS = 1500;
    /** 스크롤 중에도 내용 변경 이벤트가 이어지므로 스크롤 동작 사이 최소 간격. */
    static final long SCROLL_GAP_MS = 400;

    private static final int IDLE = 0, SEEK = 1, OBSERVE = 2;

    private static SimTapService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int phase = IDLE;
    private int jobSlot = -1;
    private int jobWidget;
    /** 데이터 칸: 맨 위까지 올린 뒤 아래로 찾는다. */
    private boolean reachedTop;
    /** 회선 칸: 누르기 전 값(0/1). */
    private int before = -1;
    private boolean sawDialog;
    private long clickAt;
    private boolean confirmClicked;
    private long lastScrollAt;
    private String lastWindowClass = "";

    private final Runnable seekTimeout = new Runnable() {
        @Override public void run() {
            int slot = phase == SEEK ? jobSlot : armedSlot();
            if (slot < 0) return;
            Log.i(TAG, "seek timeout slot=" + slot);
            disarm(SimTapService.this);
            endJob();
            toastMissing(slot);
        }
    };

    private final Runnable observeTimeout = new Runnable() {
        @Override public void run() {
            if (phase == OBSERVE) { Log.i(TAG, "observe timeout, no change"); endJob(); }
        }
    };

    static boolean isRunning() { return instance != null; }

    /** 트램펄린이 arm 을 기록하고 화면을 연 직후 부른다. */
    static void onArmed() {
        if (instance == null) return;
        instance.endJob();
        instance.handler.postDelayed(instance.seekTimeout, SEEK_MS);
    }

    static void disarm(Context ctx) {
        ctx.getSharedPreferences(TrampolineActivity.PREFS, MODE_PRIVATE).edit()
                .putLong(TrampolineActivity.ARMED_AT, 0)
                .putInt(TrampolineActivity.ARM_SLOT, -1).commit();
    }

    /** arm 기록과 진행 중 작업을 지운다. 「상태 읽어 오기」처럼 누르지 않고 화면만 열 때 부른다. */
    static void cancel(Context ctx) {
        disarm(ctx);
        if (instance != null) instance.endJob();
    }

    /** 지운 위젯의 arm 기록과 작업을 지운다. */
    static void forgetWidgets(Context ctx, int[] ids) {
        int armed = ctx.getSharedPreferences(TrampolineActivity.PREFS, MODE_PRIVATE)
                .getInt(TrampolineActivity.ARM_WIDGET, -1);
        for (int id : ids) {
            if (id == armed) disarm(ctx);
            if (instance != null && instance.phase != IDLE && instance.jobWidget == id) instance.endJob();
        }
    }

    private int armedSlot() {
        SharedPreferences p = getSharedPreferences(TrampolineActivity.PREFS, MODE_PRIVATE);
        return p.getLong(TrampolineActivity.ARMED_AT, 0) == 0 ? -1 : p.getInt(TrampolineActivity.ARM_SLOT, -1);
    }

    /** arm 이 살아 있으면 메모리 작업으로 옮기고 기록을 지운다. */
    private boolean takeArm() {
        SharedPreferences p = getSharedPreferences(TrampolineActivity.PREFS, MODE_PRIVATE);
        long at = p.getLong(TrampolineActivity.ARMED_AT, 0);
        if (at == 0 || SystemClock.elapsedRealtime() - at > ARM_WINDOW_MS) return false;
        int slot = p.getInt(TrampolineActivity.ARM_SLOT, -1);
        jobWidget = p.getInt(TrampolineActivity.ARM_WIDGET, -1);
        disarm(this);
        if (slot < 0 || slot > SimTapWidget.SLOT_DATA) return false;
        jobSlot = slot;
        phase = SEEK;
        reachedTop = false;
        Log.i(TAG, "job start widget=" + jobWidget + " slot=" + slot);
        return true;
    }

    private void endJob() {
        phase = IDLE;
        jobSlot = -1;
        before = -1;
        sawDialog = false;
        confirmClicked = false;
        handler.removeCallbacks(seekTimeout);
        handler.removeCallbacks(observeTimeout);
    }

    @Override
    protected void onServiceConnected() { instance = this; }

    @Override
    public boolean onUnbind(android.content.Intent i) { instance = null; return super.onUnbind(i); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getClassName() != null) {
            lastWindowClass = e.getClassName().toString();
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !PKG.equals(String.valueOf(root.getPackageName()))) return;

        List<AccessibilityNodeInfo> switches = switches(root);
        AccessibilityNodeInfo dataTitle = dataTitle(root);
        AccessibilityNodeInfo anchor = !switches.isEmpty() ? switches.get(0) : dataTitle;
        AccessibilityNodeInfo scroll = anchor != null ? scrollableAncestor(anchor)
                : lastWindowClass.endsWith(SIM_MGR_CLASS_SUFFIX) ? firstScrollable(root) : null;
        boolean atTop = scroll == null || !has(scroll, AccessibilityAction.ACTION_SCROLL_BACKWARD);
        boolean atEnd = scroll == null || !has(scroll, AccessibilityAction.ACTION_SCROLL_FORWARD);

        cache(switches, dataTitle, atTop, atEnd);

        if (phase == IDLE && !takeArm()) return;
        if (phase == SEEK) {
            if (anchor == null && !lastWindowClass.endsWith(SIM_MGR_CLASS_SUFFIX)) return;
            if (jobSlot == SimTapWidget.SLOT_DATA) seekData(dataTitle, scroll, atTop, atEnd);
            else seekLine(switches, dataTitle, scroll, atTop, atEnd);
        } else if (phase == OBSERVE) {
            observe(root, switches, atTop);
        }
    }

    /** 회선은 맨 위에서만 읽는다. 스크롤된 화면에서는 첫 스위치가 SIM 1 이 아닐 수 있다. */
    private void cache(List<AccessibilityNodeInfo> switches, AccessibilityNodeInfo dataTitle, boolean atTop, boolean atEnd) {
        boolean changed = false;
        if (!switches.isEmpty() && atTop) {
            List<String> names = new ArrayList<>();
            List<Boolean> on = new ArrayList<>();
            for (int i = 0; i < switches.size(); i++) {
                CharSequence d = switches.get(i).getContentDescription();
                names.add(d != null && d.length() > 0 ? d.toString() : getString(R.string.sim_default, i + 1));
                on.add(switches.get(i).isChecked());
            }
            // 데이터 행은 회선 행보다 아래다. 그것이 보이거나 더 내려갈 곳이 없으면 회선을 다 본 것이다.
            changed = SimCache.saveLines(this, names, on, atEnd || dataTitle != null);
        }
        if (dataTitle != null) {
            AccessibilityNodeInfo summary = summaryOf(dataTitle);
            CharSequence s = summary != null ? summary.getText() : null;
            changed |= SimCache.saveData(this, dataTitle.isEnabled(), s != null ? s.toString() : "");
        } else if (!switches.isEmpty() && atTop && atEnd) {
            changed |= SimCache.saveData(this, false, "");
        }
        if (changed) {
            Log.i(TAG, "cache updated");
            SimTapWidget.refresh(this);
        }
    }

    private void seekLine(List<AccessibilityNodeInfo> switches, AccessibilityNodeInfo dataTitle,
                          AccessibilityNodeInfo scroll, boolean atTop, boolean atEnd) {
        if (!atTop) { scroll(scroll, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); return; }
        if (switches.size() <= jobSlot) {
            // 목록 전체가 보이는데도 없으면 그 회선이 없다. 아니면 그려지는 중일 수 있어 기다린다.
            if (!switches.isEmpty() && (atEnd || dataTitle != null)) {
                Log.i(TAG, "line " + jobSlot + " missing, switches=" + switches.size());
                int slot = jobSlot;
                endJob();
                toastMissing(slot);
            }
            return;
        }
        AccessibilityNodeInfo sw = switches.get(jobSlot);
        AccessibilityNodeInfo row = clickableAncestor(sw);
        if (row == null) row = sw;
        int b = sw.isChecked() ? 1 : 0;
        // 누르기 전에 작업 단계를 넘겨 이어지는 이벤트가 다시 누르지 않게 한다.
        handler.removeCallbacks(seekTimeout);
        phase = OBSERVE;
        before = b;
        sawDialog = false;
        confirmClicked = false;
        handler.postDelayed(observeTimeout, OBSERVE_MS);
        clickAt = SystemClock.elapsedRealtime();
        boolean ok = row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "line " + jobSlot + " click before=" + b + " performed=" + ok);
        if (!ok) endJob();
    }

    private void seekData(AccessibilityNodeInfo dataTitle, AccessibilityNodeInfo scroll, boolean atTop, boolean atEnd) {
        if (dataTitle == null) {
            if (!reachedTop && !atTop) { scroll(scroll, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); return; }
            reachedTop = true;
            if (!atEnd) { scroll(scroll, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return; }
            if (scroll != null) { endJob(); toastMissing(SimTapWidget.SLOT_DATA); }
            return;
        }
        AccessibilityNodeInfo row = clickableAncestor(dataTitle);
        endJob();
        if (!dataTitle.isEnabled() || row == null || !row.isEnabled()) {
            Log.i(TAG, "data row not available");
            toastMissing(SimTapWidget.SLOT_DATA);
            return;
        }
        // v0.01 은 하위 화면까지만 간다. SIM 고르기와 적용은 사용자가 한다.
        Log.i(TAG, "data row click performed=" + row.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    /**
     * 확인 창이 뜨면 활성 창에 회선 스위치가 없다. 켜기 방향만 확인 버튼을 한 번 누르고 끄기는 사용자에게 맡긴다.
     * 화면으로 돌아와 값이 바뀌었으면(확인 뒤 SIM 작업이 끝난 것) 홈으로 간다.
     */
    private void observe(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> switches, boolean atTop) {
        if (switches.isEmpty()) {
            // 확인 창이든 진행 창이든 스위치가 가려진 창을 봤으면 클릭 직후의 순간 토글이 아니다.
            // 끄기 창은 뜨는 순간 이벤트가 화면 쪽으로 잡혀 확인 버튼을 못 볼 수 있다(v0.01.00.01 실기기).
            sawDialog = true;
            List<AccessibilityNodeInfo> ok = root.findAccessibilityNodeInfosByViewId(BUTTON_OK);
            if (ok == null || ok.isEmpty()) return;
            List<AccessibilityNodeInfo> cancel = root.findAccessibilityNodeInfosByViewId(BUTTON_CANCEL);
            // 취소 버튼이 없는 창(「SIM을 끌 수 없음」 등 안내만 하는 창)은 누르지 않는다.
            if (before == 0 && !confirmClicked && cancel != null && !cancel.isEmpty()) {
                confirmClicked = true;
                Log.i(TAG, "turn-on confirm click performed=" + ok.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK));
            }
            return;
        }
        // 클릭 순간의 토글은 밀리초 안에 되돌려진다. 창을 봤거나 클릭 뒤 충분히 지났으면 확인을 거친 변화다.
        // 끄기 창은 이벤트가 화면 쪽으로만 와서 창을 못 볼 수 있다(v0.01.00.04 실기기).
        boolean settled = sawDialog || SystemClock.elapsedRealtime() - clickAt >= SETTLE_MS;
        if (!settled || !atTop || switches.size() <= jobSlot) return;
        int now = switches.get(jobSlot).isChecked() ? 1 : 0;
        if (now != before) {
            Log.i(TAG, "line " + jobSlot + " changed " + before + " -> " + now);
            endJob();
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void scroll(AccessibilityNodeInfo node, int action) {
        if (node == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastScrollAt < SCROLL_GAP_MS) return;
        lastScrollAt = now;
        Log.i(TAG, "scroll " + (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? "up" : "down")
                + " performed=" + node.performAction(action));
    }

    private void toastMissing(int slot) {
        String msg = slot == SimTapWidget.SLOT_DATA ? getString(R.string.toast_data_missing)
                : getString(R.string.toast_line_missing, slot + 1);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** 회선 스위치를 화면 위에서부터의 순서로. */
    private static List<AccessibilityNodeInfo> switches(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(SWITCH_ID);
        List<AccessibilityNodeInfo> list = found != null ? new ArrayList<>(found) : new ArrayList<AccessibilityNodeInfo>();
        Collections.sort(list, new Comparator<AccessibilityNodeInfo>() {
            @Override public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                return Integer.compare(top(a), top(b));
            }
        });
        return list;
    }

    private static int top(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.top;
    }

    /** 「주 사용 SIM 카드」 아래 「모바일 데이터」 행의 제목. summary 가 붙은 것만 데이터 행으로 본다. */
    private static AccessibilityNodeInfo dataTitle(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(TITLE_ID);
        if (titles == null) return null;
        for (AccessibilityNodeInfo t : titles) {
            CharSequence c = t.getText();
            if (c == null) continue;
            String s = c.toString().trim();
            if ((s.equals("모바일 데이터") || s.equalsIgnoreCase("Mobile data")) && summaryOf(t) != null) return t;
        }
        return null;
    }

    /** 제목과 같은 행의 summary. 부모와 조부모까지만 본다(더 올라가면 다른 행의 summary 가 잡힌다). */
    private static AccessibilityNodeInfo summaryOf(AccessibilityNodeInfo title) {
        AccessibilityNodeInfo n = title.getParent();
        for (int up = 0; up < 2 && n != null; up++, n = n.getParent()) {
            List<AccessibilityNodeInfo> s = n.findAccessibilityNodeInfosByViewId(SUMMARY_ID);
            if (s != null && !s.isEmpty()) return s.get(0);
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node.getParent();
        for (int up = 0; up < 5 && n != null; up++, n = n.getParent()) {
            if (n.isClickable()) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node.getParent(); n != null; n = n.getParent()) {
            if (n.isScrollable()) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n.isScrollable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    private static boolean has(AccessibilityNodeInfo n, AccessibilityAction a) {
        return n.getActionList().contains(a);
    }

    @Override
    public void onInterrupt() { endJob(); }

    @Override
    public void onDestroy() { endJob(); instance = null; super.onDestroy(); }
}
