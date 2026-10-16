package com.tapbros.simtap;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ServiceRegression {
    private interface Case { void run() throws Exception; }
    private static int passed;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void test(String name, Case body) throws Exception {
        body.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void evaluate(SimTapService service, AccessibilityNodeInfo root, long now) throws Exception {
        SystemClock.now = now;
        service.root = root;
        Method method = SimTapService.class.getDeclaredMethod("evaluate");
        method.setAccessible(true);
        method.invoke(service);
    }

    private static AccessibilityNodeInfo node(String id) { return new AccessibilityNodeInfo(id); }

    private static AccessibilityNodeInfo panel(boolean wifi, boolean on) {
        AccessibilityNodeInfo root = node("root");
        root.pkg = SimTapService.NET_PKG;
        AccessibilityNodeInfo toggle = node(wifi ? SimTapService.NET_WIFI_TOGGLE : SimTapService.NET_MOBILE_TOGGLE);
        toggle.checked = on;
        root.add(node(SimTapService.NET_TITLE)).add(toggle).add(node(SimTapService.NET_DONE));
        return root;
    }

    private static AccessibilityNodeInfo first(AccessibilityNodeInfo root, String id) {
        return root.findAccessibilityNodeInfosByViewId(id).get(0);
    }

    private static SimTapService service() {
        SystemClock.now = 1000;
        SimTapService service = new SimTapService();
        service.onServiceConnected();
        return service;
    }

    private static void clean(SimTapService service) throws Exception {
        check(!((NetworkJob) field(service, "network")).active(), "network job retained");
        check(((Handler) field(service, "handler")).pendingCount() == 0, "callbacks retained");
        check(Arrays.equals(service.getServiceInfo().packageNames, new String[] { SimTapService.PKG }),
                "SystemUI scope retained");
    }

    private static void action(NetworkJob job, long now, boolean panel, int state, int setting,
                               boolean connected, NetworkJob.Action expected) {
        check(job.read(now, panel, state, setting, connected) == expected, "wrong network action at " + now);
    }

    private static void observe(SimTapService service, AccessibilityNodeInfo root, long now) throws Exception {
        SystemClock.now = now;
        Method method = SimTapService.class.getDeclaredMethod("observe", AccessibilityNodeInfo.class,
                List.class, AccessibilityNodeInfo.class, boolean.class);
        method.setAccessible(true);
        method.invoke(service, root, Collections.emptyList(), null, true);
    }

    private static AccessibilityNodeInfo simDialog(String title, boolean body, boolean neutral) {
        AccessibilityNodeInfo root = node("root");
        root.pkg = SimTapService.PKG;
        AccessibilityNodeInfo heading = node(SimTapService.PKG + ":id/alertTitle");
        heading.text = title;
        root.add(heading).add(node(SimTapService.BUTTON_OK)).add(node(SimTapService.BUTTON_CANCEL));
        if (body) {
            AccessibilityNodeInfo message = node("android:id/message");
            message.text = "Other line changes";
            root.add(message);
        }
        if (neutral) root.add(node(SimTapService.BUTTON_NEUTRAL));
        return root;
    }

    private static AccessibilityNodeInfo mobileDialog() {
        AccessibilityNodeInfo root = node("root");
        root.pkg = SimTapService.NET_PKG;
        root.windowId = 2;
        for (String[] value : new String[][] {
                {"android:id/alertTitle", "Turn off mobile data?"},
                {"android:id/message", "No internet with your carrier."},
                {SimTapService.BUTTON_OK, "Turn off"}, {SimTapService.BUTTON_CANCEL, "Cancel"}}) {
            AccessibilityNodeInfo child = node(value[0]);
            child.text = value[1];
            root.add(child);
        }
        return root;
    }

    public static void main(String[] args) throws Exception {
        test("cold process distrusts persisted states and retains identity", () -> {
            Context context = new Context();
            context.getSharedPreferences("sim", 0).edit().putInt("lines", 2)
                    .putString("name_0", "Work").putString("name_1", "Travel")
                    .putInt("on_0", 1).putInt("on_1", 0).commit();
            check(SimCache.on(context, 0) == -1, "persisted certainty leaked before connection");
            check("Work".equals(SimCache.name(context, 0)), "identity lost");
            check(!SimCache.canToggle(context, 0, 0, "Work"), "cold stale target toggles");
        });
        test("disconnect and reconnect invalidate certainty without losing names", () -> {
            SimTapService service = service();
            SimCache.saveLines(service, Arrays.asList("Work", "Travel"), Arrays.asList(true, false), true);
            SimCache.saveData(service, true, "Work");
            check(SimCache.canToggle(service, 0, 0, "Work"), "known cell cannot toggle");
            service.onUnbind(null);
            check(SimCache.on(service, 0) == -1 && SimCache.on(service, 1) == -1, "disconnect remained known");
            check(!SimCache.statesKnown(service), "unknown states considered complete");
            check(SimCache.lineCount(service) == 2 && "Travel".equals(SimCache.name(service, 1)), "names lost");
            check("Work".equals(SimCache.dataName(service)), "data identity lost");
            // Simulate an older persisted value when a new service object connects.
            service.getSharedPreferences("sim", 0).edit().putInt("on_0", 1).commit();
            SimTapService next = service();
            check(SimCache.on(next, 0) == -1, "reconnect trusted old storage");
            check(!next.getSharedPreferences("sim", 0).contains("on_0"), "old state not invalidated");
            next.onUnbind(null);
        });
        test("unknown and stale slot clicks read only; partial reads remain unknown", () -> {
            SimTapService service = service();
            SimCache.saveLines(service, Arrays.asList("Work"), Arrays.asList(true), false);
            check(SimCache.canToggle(service, 0, 0, "Work"), "fresh target rejected");
            check(!SimCache.canToggle(service, 0, -1, "Work"), "unknown displayed target toggles");
            check(!SimCache.canToggle(service, 0, 1, "Work"), "already-target stale cell toggles");
            check(!SimCache.canToggle(service, 0, 0, "Other"), "wrong identity toggles");
            check(!SimCache.canToggle(service, 1, 1, "Travel"), "unread slot toggles");
            check(!SimCache.statesKnown(service), "partial read restores unread certainty");
            SimCache.saveLines(service, Arrays.asList("Work", "Travel"), Arrays.asList(true, true), true);
            check(SimCache.statesKnown(service), "complete read not known");
            service.onUnbind(null);
        });
        test("network desired state and setting mismatch never toggle", () -> {
            NetworkJob job = new NetworkJob();
            job.start(false, 0, 0);
            action(job, 0, true, 1, 0, false, NetworkJob.Action.WAIT);
            action(job, 250, true, 1, 0, false, NetworkJob.Action.WAIT);
            action(job, 500, true, 0, 0, false, NetworkJob.Action.WAIT);
            action(job, 750, true, 0, 0, false, NetworkJob.Action.CLOSE);
            job.clear();
            check(!job.active() && job.deadline() == 0, "clear retained state");
        });
        test("network click commits before repeated events and requires real state", () -> {
            NetworkJob job = new NetworkJob();
            job.start(false, 0, 0);
            action(job, 0, true, 1, 1, false, NetworkJob.Action.WAIT);
            action(job, 250, true, 1, 1, false, NetworkJob.Action.CLICK);
            action(job, 250, true, 1, 1, false, NetworkJob.Action.WAIT);
            action(job, 500, true, 1, 1, false, NetworkJob.Action.WAIT);
            action(job, 750, true, 0, 1, false, NetworkJob.Action.WAIT);
            action(job, 1000, true, 0, 1, false, NetworkJob.Action.WAIT);
            action(job, 1250, true, 0, 0, false, NetworkJob.Action.CLOSE);
        });
        test("network missing switch and seek timeout leave panel untouched", () -> {
            NetworkJob job = new NetworkJob();
            job.start(true, 1, 0);
            action(job, 0, true, -1, -1, false, NetworkJob.Action.WAIT);
            action(job, 1499, true, -1, -1, false, NetworkJob.Action.WAIT);
            action(job, 1500, true, -1, -1, false, NetworkJob.Action.LEAVE);
            job.start(true, 1, 0);
            action(job, 7999, false, -1, -1, false, NetworkJob.Action.WAIT);
            action(job, 8000, false, -1, -1, false, NetworkJob.Action.LEAVE);
        });
        test("wifi connection wait is bounded and connected event closes", () -> {
            NetworkJob job = new NetworkJob();
            job.start(true, 1, 0);
            action(job, 0, true, 0, 0, false, NetworkJob.Action.WAIT);
            action(job, 250, true, 0, 0, false, NetworkJob.Action.CLICK);
            action(job, 500, true, 1, 1, false, NetworkJob.Action.WAIT);
            action(job, 750, true, 1, 1, false, NetworkJob.Action.WAIT);
            check(job.deadline() == 10750, "connection deadline wrong");
            action(job, 10749, true, 1, 1, false, NetworkJob.Action.WAIT);
            action(job, 10750, true, 1, 1, false, NetworkJob.Action.LEAVE);
            job.start(true, 1, 20000);
            action(job, 20000, true, 0, 0, false, NetworkJob.Action.WAIT);
            action(job, 20250, true, 0, 0, false, NetworkJob.Action.CLICK);
            action(job, 20500, true, 1, 1, true, NetworkJob.Action.WAIT);
            action(job, 20750, true, 1, 1, true, NetworkJob.Action.CLOSE);
        });
        test("service leaves unrelated two-button confirmation untouched after mobile off", () -> {
            SimTapService service = service();
            NetState.mobile = 1;
            check(SimTapService.startNet(SimTapWidget.SLOT_MOBILE, 0), "network start failed");
            AccessibilityNodeInfo panel = panel(false, true);
            evaluate(service, panel, 1000);
            evaluate(service, panel, 1250);
            check(first(panel, SimTapService.NET_MOBILE_TOGGLE).clicks == 1, "switch not clicked once");
            AccessibilityNodeInfo dialog = simDialog("Unrelated system action", true, false);
            dialog.pkg = SimTapService.NET_PKG;
            evaluate(service, dialog, 1500);
            evaluate(service, dialog, 1750);
            evaluate(service, dialog, 2000);
            check(first(dialog, SimTapService.BUTTON_OK).clicks == 0, "unrelated dialog confirmed");
            check(service.globalActions == 0, "unrelated dialog dismissed");
            // User confirms separately. Return to the panel and supply the new setting.
            NetState.mobile = 0;
            first(panel, SimTapService.NET_MOBILE_TOGGLE).checked = false;
            evaluate(service, panel, 2250);
            evaluate(service, panel, 2500);
            check(first(panel, SimTapService.NET_MOBILE_TOGGLE).clicks == 1, "switch clicked twice");
            check(first(panel, SimTapService.NET_DONE).clicks == 1, "completed panel not closed");
            clean(service);
            service.onUnbind(null);
        });
        test("service already-target closes without clicking switch", () -> {
            SimTapService service = service();
            NetState.mobile = 0;
            check(SimTapService.startNet(SimTapWidget.SLOT_MOBILE, 0), "start failed");
            AccessibilityNodeInfo panel = panel(false, false);
            evaluate(service, panel, 1000);
            evaluate(service, panel, 1250);
            check(first(panel, SimTapService.NET_MOBILE_TOGGLE).clicks == 0, "already-target clicked");
            clean(service);
            service.onUnbind(null);
        });
        test("recognized mobile-off dialogs keep one-tap confirmation", () -> {
            for (int variant = 0; variant < 3; variant++) {
                SimTapService service = service();
                NetState.mobile = 1;
                SimTapService.startNet(SimTapWidget.SLOT_MOBILE, 0);
                AccessibilityNodeInfo panel = panel(false, true);
                AccessibilityNodeInfo carrier = node(SimTapService.NET_PKG + ":id/mobile_title");
                carrier.text = "Work";
                panel.add(carrier);
                evaluate(service, panel, 1000);
                evaluate(service, panel, 1250);
                AccessibilityNodeInfo dialog = mobileDialog();
                if (variant == 1) first(dialog, "android:id/message").text = "No internet with Work.";
                if (variant == 2) first(dialog, "android:id/alertTitle").text = "\u200eTurn off mobile data?\u200f";
                evaluate(service, dialog, 1500);
                evaluate(service, dialog, 1749);
                check(first(dialog, SimTapService.BUTTON_OK).clicks == 0, "confirmed without stable reads");
                evaluate(service, dialog, 1750);
                evaluate(service, dialog, 2000);
                check(first(dialog, SimTapService.BUTTON_OK).clicks == 1, "recognized confirmation not exactly once");
                NetState.mobile = 0;
                first(panel, SimTapService.NET_MOBILE_TOGGLE).checked = false;
                evaluate(service, panel, 2250);
                evaluate(service, panel, 2500);
                check(first(panel, SimTapService.NET_DONE).clicks == 1, "confirmed job did not finish");
                clean(service);
                service.onUnbind(null);
            }
        });
        test("dialog semantics visibility and button shape reject unrelated actions", () -> {
            for (int variant = 0; variant < 8; variant++) {
                SimTapService service = service();
                NetState.mobile = 1;
                SimTapService.startNet(SimTapWidget.SLOT_MOBILE, 0);
                AccessibilityNodeInfo panel = panel(false, true);
                evaluate(service, panel, 1000);
                evaluate(service, panel, 1250);
                AccessibilityNodeInfo dialog = mobileDialog();
                if (variant == 0) first(dialog, "android:id/alertTitle").text = "Unrelated action";
                if (variant == 1) first(dialog, "android:id/message").text = "Erase all data?";
                if (variant == 2) first(dialog, SimTapService.BUTTON_OK).text = "Delete";
                if (variant == 3) first(dialog, SimTapService.BUTTON_CANCEL).text = "Later";
                if (variant == 4) dialog.add(node(SimTapService.BUTTON_NEUTRAL));
                if (variant == 5) first(dialog, SimTapService.BUTTON_OK).enabled = false;
                if (variant == 6) first(dialog, "android:id/message").visible = false;
                if (variant == 7) dialog.pkg = "unrelated.app";
                evaluate(service, dialog, 1500);
                evaluate(service, dialog, 1750);
                check(first(dialog, SimTapService.BUTTON_OK).clicks == 0, "unsafe dialog variant " + variant);
                SimTapService.cancel(service);
                clean(service);
                service.onUnbind(null);
            }
        });
        test("confirmation is bounded to first window mobile-off and one job", () -> {
            NetworkJob job = new NetworkJob();
            for (int variant = 0; variant < 5; variant++) {
                boolean wifi = variant == 0;
                int target = variant == 1 ? 1 : 0;
                job.start(wifi, target, 1000);
                job.read(1000, true, 1 - target, 1 - target, false);
                job.read(1250, true, 1 - target, 1 - target, false);
                if (variant == 2) job.confirm(1400, 2, false);
                if (variant == 3) {
                    job.confirm(1400, 3, false);
                    job.read(1450, true, 1, 1, false);
                }
                long at = variant == 4 ? 6251 : 1500;
                check(!job.confirm(at, 3, true), "first read confirmed");
                check(!job.confirm(at + 250, 3, true), "ineligible job confirmed " + variant);
                job.clear();
                check(!job.confirm(at + 500, 3, true), "cleared job confirmed");
            }
        });
        test("localized resources are matched and absent resources fail closed", () -> {
            Context context = new Context();
            android.content.res.Resources ui = context.getPackageManager().resources;
            android.content.res.Resources system = android.content.res.Resources.getSystem();
            ui.put("mobile_data_disable_title", "모바일 데이터를 끌까요?");
            ui.put("mobile_data_disable_message", "%1$s 데이터 연결이 끊어집니다.");
            ui.put("mobile_data_disable_message_default_carrier", "통신사");
            system.put("alert_windows_notification_turn_off_action", "사용 중지");
            system.put("cancel", "취소");
            try {
                MobileDataDialog match = MobileDataDialog.load(context, "업무");
                check(match != null && match.matches("모바일 데이터를 끌까요?", "업무 데이터 연결이 끊어집니다.",
                        "사용 중지", "취소"), "localized resources not matched");
                check(match.matches("모바일 데이터를 끌까요?", "통신사 데이터 연결이 끊어집니다.",
                        "사용 중지", "취소"), "fallback carrier not matched");
                check(!match.matches("모바일 데이터를 끌까요?", "모든 데이터를 삭제합니다.",
                        "사용 중지", "취소"), "different body matched");
                ui.remove("mobile_data_disable_title");
                check(MobileDataDialog.load(context, "업무") == null, "missing resource accepted");
                context.getPackageManager().unavailable = true;
                check(MobileDataDialog.load(context, "업무") == null, "unavailable package accepted");
            } finally {
                system.put("alert_windows_notification_turn_off_action", "Turn off");
                system.put("cancel", "Cancel");
            }
        });
        test("service seek timeout removes callbacks and restores scope", () -> {
            SimTapService service = service();
            check(!SimTapService.startNet(0, 1), "invalid network slot accepted");
            check(SimTapService.startNet(SimTapWidget.SLOT_WIFI, 1), "start failed");
            check(service.getServiceInfo().packageNames.length == 2, "scope not widened");
            ((Handler) field(service, "handler")).runDue(9000);
            clean(service);
            check(service.globalActions == 0, "timeout pressed back or home");
            service.onUnbind(null);
        });
        test("service observe timeout and cancel clean network callbacks", () -> {
            SimTapService service = service();
            NetState.mobile = 1;
            SimTapService.startNet(SimTapWidget.SLOT_MOBILE, 0);
            AccessibilityNodeInfo panel = panel(false, true);
            evaluate(service, panel, 1000);
            evaluate(service, panel, 1250);
            ((Handler) field(service, "handler")).runDue(16250);
            clean(service);
            check(first(panel, SimTapService.NET_DONE).clicks == 0, "timeout dismissed panel");
            SimTapService.startNet(SimTapWidget.SLOT_WIFI, 0);
            SimTapService.cancel(service);
            clean(service);
            service.onUnbind(null);
        });
        test("SIM turn-off auto-confirm and safety checks remain intact", () -> {
            for (int scenario = 0; scenario < 5; scenario++) {
                SimTapService service = service();
                set(service, "before", scenario == 2 ? 0 : 1);
                set(service, "targetName", "Work");
                set(service, "autoEligible", true);
                set(service, "clickAt", 1000L);
                set(service, "sawOtherActivity", scenario == 4);
                AccessibilityNodeInfo dialog = simDialog(scenario == 1 ? "Travel" : "Work",
                        true, scenario == 3);
                observe(service, dialog, 1250);
                observe(service, dialog, 1500);
                check(first(dialog, SimTapService.BUTTON_OK).clicks == (scenario == 0 ? 1 : 0),
                        "SIM confirmation policy changed for scenario " + scenario);
                service.onUnbind(null);
            }
        });
        System.out.println("ServiceRegression: " + passed + " cases passed, 0 failures");
    }
}
