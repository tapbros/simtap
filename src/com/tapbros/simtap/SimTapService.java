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
 * 2) 위젯 칸이 arm 한 뒤에만 그 칸의 행을 한 번 누른다. 회선은 화면 순번이 아니라 탭할 때 위젯이 보인 SIM 이름으로
 *    찾는다. 유심이 없으면 화면 0번째 스위치가 eSIM 일 수 있기 때문이다. arm 은 첫 이벤트에서 메모리 작업으로 옮기고 바로 지우므로
 *    스크롤이나 클릭이 다시 부르는 이벤트가 같은 arm 으로 또 누르지 않는다.
 */
public class SimTapService extends AccessibilityService {
    static final String TAG = "SimTap";
    /** 대상 앱 패키지. build.sh 가 만드는 TargetConfig 에서 온다(릴리스는 telephonyui, 시험 빌드는 SIMTAP_TARGET_PKG). */
    static final String PKG = TargetConfig.PKG;
    static final String SWITCH_ID = PKG + ":id/on_off_switch";
    /** 설정 행의 제목과 요약. One UI 9.0 Fold8 의 「모바일 데이터」 행은 android 쪽 id 를 쓴다(실기기 덤프). */
    static final String[] TITLE_IDS = { "android:id/title", PKG + ":id/title" };
    static final String[] SUMMARY_IDS = { "android:id/summary", PKG + ":id/summary" };
    static final String BUTTON_OK = "android:id/button1";
    static final String BUTTON_CANCEL = "android:id/button2";
    /** 세 번째 버튼. 최대 개수 창처럼 버튼이 3개인 창은 자동 확인하지 않는다. */
    static final String BUTTON_NEUTRAL = "android:id/button3";
    /** 창이 닫히고 값이 그대로인 상태가 이만큼 이어져야 취소로 본다. 닫힘 이벤트가 값 갱신보다 앞설 수 있다. */
    static final long CANCEL_GRACE_MS = 3000;
    /** AppCompat 창 제목은 앱 쪽 id 다(실기기 덤프 com.samsung.android.app.telephonyui:id/alertTitle). 프레임워크 창 대비로 android id 도 본다. */
    static final String[] ALERT_TITLES = { PKG + ":id/alertTitle", "android:id/alertTitle" };
    static final String SIM_MGR_CLASS_SUFFIX = "SimCardMgrActivity";
    /** arm 뒤 이 안에 SIM 관리자 이벤트가 와야 작업을 시작한다. */
    static final long ARM_WINDOW_MS = 10000;
    /** arm 뒤 이 안에 행을 누르지 못하면 토스트로 알린다. 스크롤 시간을 포함한다. */
    static final long SEEK_MS = 8000;
    /** 행을 누른 뒤 값 변화를 기다리는 시간. 끄기 확인은 사용자가 누르므로 넉넉히 둔다. */
    static final long OBSERVE_MS = 30000;
    /** 확인 버튼이 눌렸거나 진행 창을 본 뒤의 대기 시간. eSIM 단말은 SIM 작업 완료까지 최대 180초 걸린다. */
    static final long OBSERVE_CONFIRMED_MS = 180000;
    /** 스위치를 누른 뒤 이 안에 처음 뜬 창만 자동 확인 대상이다. */
    static final long AUTO_CONFIRM_WINDOW_MS = 5000;
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
    /** 회선 칸: 위젯이 정한 목표값(0/1), 모르면 -1(토글). */
    private int jobTarget = -1;
    /** 회선 칸: 탭할 때 위젯 칸이 보인 캐시 SIM 이름(방향 문자 제거). 스위치 contentDescription 과 켜기 창 제목에 대조한다. */
    private String targetName = "";
    /** 회선 칸: 캐시에 targetName 과 같은 이름이 여럿이다. 그때는 맨 위 화면의 jobSlot 번째만 쓴다. */
    private boolean dupName;
    /** 회선 칸: 캐시의 다른 회선 이름 중 targetName 을 포함하는 더 긴 이름. 그 이름이 창 제목에 있으면 자동 확인하지 않는다. */
    private final List<String> longerNames = new ArrayList<>();
    /** 켜기 자동 확인을 아직 할 수 있다. 클릭 뒤 5초 안에 처음 뜬 창이 닫히기 전까지만 true. */
    private boolean autoEligible;
    /** 확인 버튼이 눌렸거나(자동·사용자) 진행 창을 봤다. 대기 시간을 늘리고 취소 판정을 하지 않는다. */
    private boolean confirmed;
    /** 창이 닫히고 값이 그대로인 상태를 처음 본 시각. 0 이면 아직 못 봤다. */
    private long unchangedSince;
    private String lastUnmatchedTitle;
    private boolean sawDialog;
    private long clickAt;
    /** 켜기 창이 그려지는 중이면 이벤트가 더 오지 않을 수 있어 짧게 다시 읽는다. */
    static final long RECHECK_MS = 250;
    private int recheckLeft;
    private String matchedOnce;
    private long matchedAt;
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            if (phase != OBSERVE || confirmClicked || !autoEligible) return;
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null || !PKG.equals(String.valueOf(r.getPackageName()))) return;
            List<AccessibilityNodeInfo> sw = switches(r);
            if (sw.isEmpty()) observe(r, sw, null, true);
        }
    };
    private boolean confirmClicked;
    private long lastScrollAt;
    private String lastWindowClass = "";

    private final Runnable seekTimeout = new Runnable() {
        @Override public void run() {
            // arm 이 만료돼 지워졌어도(takeArm) onArmed 가 jobSlot 에 칸을 남겨 두므로 안내가 나온다.
            if (phase == OBSERVE) return;
            int slot = jobSlot;
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

    /** 트램펄린이 arm 을 기록하고 화면을 연 직후 부른다. 시간 초과 안내에 쓸 칸을 메모리에 둔다. */
    static void onArmed(int slot) {
        if (instance == null) return;
        instance.endJob();
        instance.jobSlot = slot;
        instance.handler.postDelayed(instance.seekTimeout, SEEK_MS);
    }

    static void disarm(Context ctx) {
        ctx.getSharedPreferences(TrampolineActivity.PREFS, MODE_PRIVATE).edit()
                .putLong(TrampolineActivity.ARMED_AT, 0)
                .putInt(TrampolineActivity.ARM_SLOT, -1)
                .putInt(TrampolineActivity.ARM_TARGET, -1)
                .remove(TrampolineActivity.ARM_NAME).commit();
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

    /** arm 이 살아 있으면 메모리 작업으로 옮기고 기록을 지운다. */
    private boolean takeArm() {
        SharedPreferences p = getSharedPreferences(TrampolineActivity.PREFS, MODE_PRIVATE);
        long at = p.getLong(TrampolineActivity.ARMED_AT, 0);
        long now = SystemClock.elapsedRealtime();
        // 재부팅하면 elapsedRealtime 이 작아져 now - at 이 음수가 된다. 그런 arm 도 지운다.
        if (at == 0 || now < at || now - at > ARM_WINDOW_MS) { disarm(this); return false; }
        int slot = p.getInt(TrampolineActivity.ARM_SLOT, -1);
        jobWidget = p.getInt(TrampolineActivity.ARM_WIDGET, -1);
        int target = p.getInt(TrampolineActivity.ARM_TARGET, -1);
        String name = p.getString(TrampolineActivity.ARM_NAME, null);
        disarm(this);
        if (slot < 0 || slot > SimTapWidget.SLOT_DATA) return false;
        if (slot != SimTapWidget.SLOT_DATA) {
            // 상태나 이름을 모르는 칸은 누르지 않는다. 트램펄린이 이미 거르지만 낡은 arm 에 대비한다.
            name = name != null ? stripBidi(name) : "";
            if (target < 0 || name.isEmpty()) { Log.i(TAG, "arm without name/target, ignored"); return false; }
            targetName = name;
            int same = 0;
            longerNames.clear();
            for (int i = 0; i < SimCache.lineCount(this); i++) {
                String n = SimCache.name(this, i);
                n = n != null ? stripBidi(n) : "";
                if (n.equals(name)) same++;
                else if (n.length() > name.length() && n.contains(name)) longerNames.add(n);
            }
            dupName = same > 1;
        }
        jobSlot = slot;
        jobTarget = slot == SimTapWidget.SLOT_DATA ? -1 : target;
        phase = SEEK;
        reachedTop = false;
        Log.i(TAG, "job start widget=" + jobWidget + " slot=" + slot + " target=" + jobTarget + " name=" + targetName + " dup=" + dupName);
        return true;
    }

    private void endJob() {
        if (phase != IDLE) {
            StackTraceElement[] st = new Throwable().getStackTrace();
            String from = st.length > 1 ? st[1].getMethodName() + ":" + st[1].getLineNumber() : "?";
            Log.i(TAG, "end job phase=" + phase + " from " + from);
        }
        phase = IDLE;
        jobSlot = -1;
        before = -1;
        jobTarget = -1;
        targetName = "";
        dupName = false;
        longerNames.clear();
        lastUnmatchedTitle = null;
        sawDialog = false;
        confirmClicked = false;
        autoEligible = false;
        confirmed = false;
        unchangedSince = 0;
        handler.removeCallbacks(seekTimeout);
        handler.removeCallbacks(observeTimeout);
        handler.removeCallbacks(recheck);
        handler.removeCallbacks(reevaluate);
        matchedOnce = null;
        recheckLeft = 0;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // 서비스가 새로 붙기 전(재부팅, 재설치)에 남은 arm 은 지금 탭이 아니다.
        disarm(this);
        // 강제 중지 뒤에는 위젯 버튼이 무효가 된다. 서비스가 다시 붙을 때 새 버튼으로 다시 그린다(v0.01.00.17 실기기).
        SimTapWidget.refresh(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent i) { endJob(); instance = null; return super.onUnbind(i); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getClassName() != null
                && PKG.equals(String.valueOf(e.getPackageName()))) {
            lastWindowClass = e.getClassName().toString();
        }
        evaluate();
    }

    /** 스크롤 뒤에는 이벤트가 더 오지 않을 수 있어(v0.01.00.12~13 실기기, 커버 화면) 잠시 뒤 화면을 다시 읽는다. */
    private final Runnable reevaluate = new Runnable() {
        @Override public void run() { if (phase != IDLE) evaluate(); }
    };

    private void evaluate() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !PKG.equals(String.valueOf(root.getPackageName()))) return;

        List<AccessibilityNodeInfo> switches = switches(root);
        AccessibilityNodeInfo dataTitle = dataTitle(root);
        AccessibilityNodeInfo anchor = !switches.isEmpty() ? switches.get(0) : dataTitle;
        AccessibilityNodeInfo scroll = anchor != null ? scrollableAncestor(anchor)
                : lastWindowClass.endsWith(SIM_MGR_CLASS_SUFFIX) ? scrollableForTop(root) : null;
        boolean atTop = scroll == null || !has(scroll, AccessibilityAction.ACTION_SCROLL_BACKWARD);
        boolean atEnd = scroll == null || !has(scroll, AccessibilityAction.ACTION_SCROLL_FORWARD);

        // scroll 이 첫 회선 스위치의 scrollable 조상일 때만 atEnd 를 회선 목록 끝으로 본다(cache 주석).
        boolean linesAtEnd = !switches.isEmpty() && scroll != null && atEnd;
        cache(switches, dataTitle, atTop, atEnd, linesAtEnd);

        if (phase == IDLE && !takeArm()) return;
        if (phase == SEEK) {
            if (anchor == null && !lastWindowClass.endsWith(SIM_MGR_CLASS_SUFFIX)) return;
            // 아래로 찾을 때는 바깥 앱 바 층이 아니라 실제로 더 내려갈 수 있는 가장 안쪽 층을 본다.
            AccessibilityNodeInfo down = anchor != null ? scroll : deepestScrollable(root, AccessibilityAction.ACTION_SCROLL_FORWARD);
            boolean downAtEnd = down == null || !has(down, AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (jobSlot == SimTapWidget.SLOT_DATA) seekData(dataTitle, scroll, atTop, down, downAtEnd);
            else seekLine(switches, dataTitle, scroll, atTop, down, downAtEnd);
            // 기다리는 분기나 간격 제한에 걸린 스크롤 뒤에는 이벤트가 더 오지 않을 수 있다. 시간 초과 전까지 다시 읽는다.
            if (phase == SEEK) {
                handler.removeCallbacks(reevaluate);
                handler.postDelayed(reevaluate, SCROLL_GAP_MS + 100);
            }
        } else if (phase == OBSERVE) {
            observe(root, switches, scroll, atTop);
        }
    }

    /** 회선은 맨 위에서만 읽는다. 스크롤된 화면에서는 첫 스위치가 SIM 1 이 아닐 수 있다. */
    private void cache(List<AccessibilityNodeInfo> switches, AccessibilityNodeInfo dataTitle, boolean atTop, boolean atEnd,
                       boolean linesAtEnd) {
        boolean changed = false;
        if (!switches.isEmpty() && atTop) {
            List<String> names = new ArrayList<>();
            List<Boolean> on = new ArrayList<>();
            for (int i = 0; i < switches.size(); i++) {
                CharSequence d = switches.get(i).getContentDescription();
                names.add(d != null && d.length() > 0 ? d.toString() : getString(R.string.sim_default, i + 1));
                on.add(switches.get(i).isChecked());
            }
            // 데이터 행은 회선 행보다 아래다. 그것이 보이거나 맨 위에서 목록 끝까지 한 화면에 보이면 회선을 다 본 것이다.
            // 단일 SIM 에서도 데이터 행은 비활성으로 있다(실기기). 이 분기는 atTop 일 때만 온다.
            // linesAtEnd 는 첫 회선 스위치의 가장 가까운 scrollable 조상(안쪽 recycler_view) 기준이다. on_off_switch 는
            // SIM 관리자 첫 화면의 회선 행에서만 쓰인다(telephonyui 디컴파일 res/6Z.xml 한 곳, AbstractSimOnOffMainPreference).
            // 그래서 다른 화면의 목록 끝을 보고 SIM 2 칸을 숨기는 일(0da6209 의 우려)은 생기지 않는다.
            // 조상 scrollable 이 없으면(scroll == null) atEnd 는 판정 근거가 아니므로 쓰지 않는다.
            changed = SimCache.saveLines(this, names, on, dataTitle != null || linesAtEnd);
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

    /**
     * 맨 위까지 올린 뒤 위에서 아래로 targetName 스위치를 찾는다. 같은 이름이 여럿이면 맨 위 화면의 jobSlot 번째가
     * 그 이름일 때만 쓰고 아니면 찾지 못함으로 끝낸다(순번은 맨 위에서만 뜻이 있다).
     */
    private void seekLine(List<AccessibilityNodeInfo> switches, AccessibilityNodeInfo dataTitle,
                          AccessibilityNodeInfo scroll, boolean atTop, AccessibilityNodeInfo down, boolean downAtEnd) {
        if (!reachedTop) {
            if (!atTop) { scroll(scroll, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); return; }
            // 목록이 아직 그려지지 않았으면 기다린다.
            if (switches.isEmpty() && dataTitle == null) return;
            reachedTop = true;
        }
        boolean dup = dupName || countNamed(switches) > 1;
        AccessibilityNodeInfo sw = findLine(switches, atTop, dup);
        if (sw == null) {
            if (!dup && !downAtEnd) { scroll(down, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return; }
            // 스위치가 하나도 안 보이면 데이터 행이 보일 때(첫 화면이 다 그려진 상태)만 찾지 못함으로 본다. 아니면 기다린다.
            if (!dup && switches.isEmpty() && dataTitle == null) return;
            Log.i(TAG, "line " + jobSlot + " name=" + targetName + " missing, dup=" + dup + " switches=" + switches.size());
            int slot = jobSlot;
            endJob();
            toastMissing(slot);
            SimTapWidget.refresh(this);
            return;
        }
        AccessibilityNodeInfo row = clickableAncestor(sw);
        if (row == null) row = sw;
        int b = sw.isChecked() ? 1 : 0;
        if (jobTarget >= 0 && b == jobTarget) {
            // 위젯이 보인 상태가 낡았다. 맨 위에서 본 값이면 이 이벤트의 cache() 가 캐시와 위젯을 이미 갱신했다.
            Log.i(TAG, "line " + jobSlot + " already target=" + jobTarget);
            endJob();
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        // 누르기 전에 작업 단계를 넘겨 이어지는 이벤트가 다시 누르지 않게 한다.
        handler.removeCallbacks(seekTimeout);
        phase = OBSERVE;
        before = b;
        lastUnmatchedTitle = null;
        sawDialog = false;
        confirmClicked = false;
        confirmed = false;
        autoEligible = true;
        handler.postDelayed(observeTimeout, OBSERVE_MS);
        clickAt = SystemClock.elapsedRealtime();
        recheckLeft = 8;
        matchedOnce = null;
        boolean ok = row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "line " + jobSlot + " click before=" + b + " performed=" + ok);
        if (!ok) endJob();
    }

    /** 스위치의 SIM 이름(contentDescription, 방향 문자 제거). 없으면 "". */
    private static String lineName(AccessibilityNodeInfo sw) {
        CharSequence d = sw.getContentDescription();
        return d != null ? stripBidi(d.toString()) : "";
    }

    private int countNamed(List<AccessibilityNodeInfo> switches) {
        int n = 0;
        for (AccessibilityNodeInfo sw : switches) if (targetName.equals(lineName(sw))) n++;
        return n;
    }

    /** 보이는 스위치 중 targetName 인 것. 같은 이름이 여럿이면(dup) 맨 위 화면의 jobSlot 번째가 그 이름일 때만. 없으면 null. */
    private AccessibilityNodeInfo findLine(List<AccessibilityNodeInfo> switches, boolean atTop, boolean dup) {
        if (targetName.isEmpty()) return null;
        if (dup) {
            if (!atTop || switches.size() <= jobSlot) return null;
            AccessibilityNodeInfo sw = switches.get(jobSlot);
            return targetName.equals(lineName(sw)) ? sw : null;
        }
        for (AccessibilityNodeInfo sw : switches) if (targetName.equals(lineName(sw))) return sw;
        return null;
    }

    /** 확인 버튼이 눌렸거나 진행 창을 봤다. 한 번만 대기 시간을 180초로 늘린다. */
    private void markConfirmed(String why) {
        if (confirmed) return;
        confirmed = true;
        handler.removeCallbacks(observeTimeout);
        handler.postDelayed(observeTimeout, OBSERVE_CONFIRMED_MS);
        Log.i(TAG, "confirmed (" + why + "), wait up to " + OBSERVE_CONFIRMED_MS + "ms");
    }

    private void seekData(AccessibilityNodeInfo dataTitle, AccessibilityNodeInfo scroll, boolean atTop,
                          AccessibilityNodeInfo down, boolean atEnd) {
        if (dataTitle == null) {
            if (!reachedTop && !atTop) { scroll(scroll, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); return; }
            reachedTop = true;
            if (!atEnd) { scroll(down, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return; }
            if (scroll != null || down != null) { endJob(); toastMissing(SimTapWidget.SLOT_DATA); }
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
    private void observe(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> switches,
                         AccessibilityNodeInfo scroll, boolean atTop) {
        if (switches.isEmpty()) {
            // 확인 창이든 진행 창이든 스위치가 가려진 창을 봤으면 클릭 직후의 순간 토글이 아니다.
            // 끄기 창은 뜨는 순간 이벤트가 화면 쪽으로 잡혀 확인 버튼을 못 볼 수 있다(v0.01.00.01 실기기).
            if (!sawDialog) {
                sawDialog = true;
                // 스위치를 누른 뒤 5초가 지나서 처음 뜬 창은 이 클릭의 켜기 창이라고 보지 않는다.
                if (SystemClock.elapsedRealtime() - clickAt > AUTO_CONFIRM_WINDOW_MS) autoEligible = false;
            }
            // 창이 다시 떴으면 취소 유예를 처음부터 센다.
            unchangedSince = 0;
            List<AccessibilityNodeInfo> ok = root.findAccessibilityNodeInfosByViewId(BUTTON_OK);
            // 확인을 누르면 창은 닫히지 않고 스피너를 보이다가 SIM 작업이 끝나면 닫힌다. 그 진행 창을 봤으면 확인된 것이다.
            if (showsProgress(root, ok)) markConfirmed("progress");
            if (ok == null || ok.isEmpty()) return;
            List<AccessibilityNodeInfo> cancel = root.findAccessibilityNodeInfosByViewId(BUTTON_CANCEL);
            List<AccessibilityNodeInfo> neutral = root.findAccessibilityNodeInfosByViewId(BUTTON_NEUTRAL);
            // 취소 버튼이 없는 창(「SIM을 끌 수 없음」 등 안내만 하는 창)과 버튼이 3개인 창(최대 개수 창)은 누르지 않는다.
            if (before == 0 && autoEligible && !confirmClicked && cancel != null && !cancel.isEmpty()
                    && (neutral == null || neutral.isEmpty())) {
                // 창 제목이 누른 스위치의 SIM 이름을 담을 때만 누른다. 아니면 사용자에게 맡긴다(값 변화로 판정).
                // 본문이 있는 창도 누르지 않는다. eSIM 테스트 프로필·고정 경고, 다른 SIM 을 끄는 켜기 창은 모두
                // 본문이 있고 정상 켜기 창(s6/c0 기본)은 본문이 없다(telephonyui 디컴파일, 실기기 단일 SIM 확인).
                // 그 본문은 모두 AlertDialog.setMessage 로 들어가 android:id/message 에 그려진다
                // (테스트 프로필·고정 eSIM c8/i.java, s6 계열 v6/e.java:32,52).
                String title = dialogTitle(root);
                List<AccessibilityNodeInfo> msg = root.findAccessibilityNodeInfosByViewId("android:id/message");
                boolean hasBody = false;
                if (msg != null) for (AccessibilityNodeInfo m : msg) {
                    CharSequence t = m.getText();
                    if (t != null && t.toString().trim().length() > 0) { hasBody = true; break; }
                }
                // 다른 회선 이름이 대상 이름을 포함하면(「SKT」와 「SKT 업무」) 그 더 긴 이름이 제목에 있을 때 거부한다.
                boolean longer = false;
                if (title != null) for (String n : longerNames) if (title.contains(n)) { longer = true; break; }
                if (targetName.isEmpty() || title == null || !title.contains(targetName) || longer || hasBody) {
                    matchedOnce = null;
                    // 제목이나 본문이 아직 그려지지 않았을 수 있다(v0.01.00.09 실기기 title=null). 잠시 뒤 다시 읽는다.
                    if (recheckLeft > 0) { recheckLeft--; handler.removeCallbacks(recheck); handler.postDelayed(recheck, RECHECK_MS); }
                    if (!String.valueOf(title).equals(lastUnmatchedTitle)) {
                        lastUnmatchedTitle = String.valueOf(title);
                        Log.i(TAG, "turn-on dialog not matched title=" + title + " name=" + targetName + " longer=" + longer + " body=" + hasBody);
                    }
                    return;
                }
                // 본문이 제목보다 늦게 그려질 수 있어 RECHECK_MS 이상 떨어진 두 번의 읽기가 모두 일치할 때만 누른다.
                // 같은 프레임의 이벤트 두 개로 바로 누르지 않게 시간 간격을 강제한다(Fable 리뷰).
                long nowMs = SystemClock.elapsedRealtime();
                if (!title.equals(matchedOnce) || nowMs - matchedAt < RECHECK_MS) {
                    if (!title.equals(matchedOnce)) { matchedOnce = title; matchedAt = nowMs; }
                    handler.removeCallbacks(recheck);
                    handler.postDelayed(recheck, RECHECK_MS);
                    return;
                }
                confirmClicked = true;
                boolean done = ok.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "turn-on confirm click performed=" + done);
                if (done) markConfirmed("auto");
            }
            return;
        }
        // 클릭 순간의 토글은 밀리초 안에 되돌려진다. 창을 봤거나 클릭 뒤 충분히 지났으면 확인을 거친 변화다.
        // 끄기 창은 이벤트가 화면 쪽으로만 와서 창을 못 볼 수 있다(v0.01.00.04 실기기).
        // 처음 뜬 창이 닫혔다. 이후 뜨는 창은 자동 확인하지 않는다.
        if (sawDialog) autoEligible = false;
        long since = SystemClock.elapsedRealtime() - clickAt;
        boolean settled = sawDialog || since >= SETTLE_MS;
        if (!settled) return;
        // 누를 때와 같은 이름으로 다시 찾는다. 안 보이면(SIM 을 끄면 목록이 밀릴 수 있다) 맨 위로 올리고 다음 이벤트에서 다시 본다.
        AccessibilityNodeInfo sw = findLine(switches, atTop, dupName || countNamed(switches) > 1);
        if (sw == null) {
            if (!atTop) { Log.i(TAG, "observe: not found, scroll up"); scroll(scroll, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); }
            else Log.i(TAG, "observe: line not visible, switches=" + switches.size());
            return;
        }
        int now = sw.isChecked() ? 1 : 0;
        if (now != before) {
            Log.i(TAG, "line " + jobSlot + " changed " + before + " -> " + now);
            endJob();
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else if (sawDialog && !confirmed) {
            // 확인 없이 창이 닫히고 값이 그대로다. 그 상태가 CANCEL_GRACE_MS 이어지면 사용자가 취소한 것으로 보고
            // 홈으로 가지 않고 끝낸다. 그 사이 값이 바뀌면 위 분기로 홈에 간다.
            long nowMs = SystemClock.elapsedRealtime();
            if (unchangedSince == 0) unchangedSince = nowMs;
            long wait = Math.max(SETTLE_MS - since, CANCEL_GRACE_MS - (nowMs - unchangedSince));
            if (wait <= 0) { Log.i(TAG, "line " + jobSlot + " dialog closed without change, cancelled"); endJob(); }
            else { handler.removeCallbacks(reevaluate); handler.postDelayed(reevaluate, wait + 100); }
        }
    }

    /** 확인 창이 진행 중인가. 확인 버튼이 꺼졌거나 ProgressBar 가 보이면 그렇다고 본다(실기기 미확인 추정). */
    private static boolean showsProgress(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> ok) {
        if (ok != null && !ok.isEmpty() && !ok.get(0).isEnabled()) return true;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            CharSequence c = n.getClassName();
            if (c != null && c.toString().endsWith("ProgressBar") && n.isVisibleToUser()) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        return false;
    }

    private void scroll(AccessibilityNodeInfo node, int action) {
        if (node == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastScrollAt < SCROLL_GAP_MS) {
            // 간격 제한으로 건너뛴 스크롤도 이벤트가 더 오지 않으면 멈추므로 간격이 지난 뒤 다시 읽는다.
            handler.removeCallbacks(reevaluate);
            handler.postDelayed(reevaluate, SCROLL_GAP_MS - (now - lastScrollAt) + 100);
            return;
        }
        lastScrollAt = now;
        Log.i(TAG, "scroll " + (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? "up" : "down")
                + " performed=" + node.performAction(action));
        handler.removeCallbacks(reevaluate);
        handler.postDelayed(reevaluate, SCROLL_GAP_MS + 100);
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
        for (String id : TITLE_IDS) {
            List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(id);
            if (titles == null) continue;
            for (AccessibilityNodeInfo t : titles) {
                CharSequence c = t.getText();
                if (c == null) continue;
                String s = c.toString().trim();
                if ((s.equals("모바일 데이터") || s.equalsIgnoreCase("Mobile data")) && summaryOf(t) != null) return t;
            }
        }
        return null;
    }

    /** 확인 창 제목에서 방향 제어 문자를 뺀 값. 제목이 없으면 null. */
    private static String dialogTitle(AccessibilityNodeInfo root) {
        for (String id : ALERT_TITLES) {
            List<AccessibilityNodeInfo> t = root.findAccessibilityNodeInfosByViewId(id);
            if (t != null && !t.isEmpty() && t.get(0).getText() != null) return stripBidi(t.get(0).getText().toString());
        }
        return null;
    }

    /** U+200E, U+200F, U+061C, U+202A..U+202E, U+2066..U+2069 를 뺀다(삼성 창 제목 앞에 U+200E 가 붙는다). */
    private static String stripBidi(String s) {
        return s.replaceAll("[\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
    }

    /**
     * 제목과 같은 행의 summary. 부모와 조부모까지만 본다(더 올라가면 다른 행의 summary 가 잡힌다).
     * 스크롤 가능한 노드(목록 자체)에 닿으면 멈춘다.
     */
    private static AccessibilityNodeInfo summaryOf(AccessibilityNodeInfo title) {
        AccessibilityNodeInfo n = title.getParent();
        for (int up = 0; up < 2 && n != null && !n.isScrollable(); up++, n = n.getParent()) {
            for (String id : SUMMARY_IDS) {
                List<AccessibilityNodeInfo> s = n.findAccessibilityNodeInfosByViewId(id);
                if (s != null && !s.isEmpty()) return s.get(0);
            }
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

    /**
     * 기준 줄이 안 보일 때 고를 스크롤 층. 바깥 앱 바 층이 맨 위여도 안쪽 목록은 아래로 밀려 있을 수 있어
     * (v0.01.00.14 실기기 커버 화면) 위로 더 갈 수 있는 층을 먼저 고른다.
     */
    private static AccessibilityNodeInfo scrollableForTop(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n.isScrollable() && has(n, AccessibilityAction.ACTION_SCROLL_BACKWARD)) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return firstScrollable(root);
    }

    /** 그 방향으로 움직일 수 있는 scrollable 가운데 BFS 로 마지막에 만나는(가장 안쪽) 층. 없으면 null. */
    private static AccessibilityNodeInfo deepestScrollable(AccessibilityNodeInfo root, AccessibilityAction a) {
        AccessibilityNodeInfo found = null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n.isScrollable() && has(n, a)) found = n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return found;
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
