package android.accessibilityservice;

import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public abstract class AccessibilityService extends Context {
    public static final int GLOBAL_ACTION_HOME = 2, GLOBAL_ACTION_BACK = 1;
    public AccessibilityNodeInfo root;
    public int globalActions;
    private AccessibilityServiceInfo info = new AccessibilityServiceInfo();
    public AccessibilityNodeInfo getRootInActiveWindow() { return root; }
    public AccessibilityServiceInfo getServiceInfo() { return info; }
    public void setServiceInfo(AccessibilityServiceInfo value) { info = value; }
    public boolean performGlobalAction(int action) { globalActions++; return true; }
    protected void onServiceConnected() {}
    public boolean onUnbind(Intent intent) { return false; }
    public void onDestroy() {}
    public abstract void onAccessibilityEvent(AccessibilityEvent event);
    public abstract void onInterrupt();
}
