package android.view.accessibility;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

public final class AccessibilityNodeInfo {
    public static final int ACTION_CLICK = 16, ACTION_SCROLL_BACKWARD = 8192, ACTION_SCROLL_FORWARD = 4096;
    public static final class AccessibilityAction {
        public static final AccessibilityAction ACTION_SCROLL_BACKWARD = new AccessibilityAction();
        public static final AccessibilityAction ACTION_SCROLL_FORWARD = new AccessibilityAction();
    }
    public String id, text, description, pkg, className = "";
    public boolean checked, clickable, scrollable, enabled = true, visible = true, clickResult = true;
    public int clicks;
    public int windowId = 1;
    public int getWindowId() { return windowId; }
    private AccessibilityNodeInfo parent;
    private final List<AccessibilityNodeInfo> children = new ArrayList<>();
    public AccessibilityNodeInfo(String id) { this.id = id; }
    public AccessibilityNodeInfo add(AccessibilityNodeInfo child) {
        children.add(child); child.parent = this; return this;
    }
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String match) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        if (match.equals(id)) found.add(this);
        for (AccessibilityNodeInfo child : children) found.addAll(child.findAccessibilityNodeInfosByViewId(match));
        return found;
    }
    public boolean performAction(int action) {
        if (action == ACTION_CLICK) clicks++;
        return clickResult;
    }
    public CharSequence getPackageName() { return pkg; }
    public CharSequence getText() { return text; }
    public CharSequence getContentDescription() { return description; }
    public CharSequence getClassName() { return className; }
    public boolean isEnabled() { return enabled; }
    public boolean isChecked() { return checked; }
    public boolean isVisibleToUser() { return visible; }
    public boolean isClickable() { return clickable; }
    public boolean isScrollable() { return scrollable; }
    public AccessibilityNodeInfo getParent() { return parent; }
    public int getChildCount() { return children.size(); }
    public AccessibilityNodeInfo getChild(int index) { return children.get(index); }
    public void getBoundsInScreen(Rect rect) { rect.top = 0; }
    public List<AccessibilityAction> getActionList() { return new ArrayList<>(); }
}
