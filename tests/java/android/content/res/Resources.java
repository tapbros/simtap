package android.content.res;
import java.util.HashMap;
import java.util.Map;

public final class Resources {
    public static final class NotFoundException extends RuntimeException {}
    private static final Resources SYSTEM = new Resources();
    static {
        SYSTEM.put("cancel", "Cancel");
        SYSTEM.put("alert_windows_notification_turn_off_action", "Turn off");
    }
    private final Map<String, Integer> identifiers = new HashMap<>();
    private final Map<Integer, String> values = new HashMap<>();
    public void put(String name, String value) {
        int id = identifiers.containsKey(name) ? identifiers.get(name) : identifiers.size() + 1;
        identifiers.put(name, id);
        values.put(id, value);
    }
    public void remove(String name) { identifiers.remove(name); }
    public static Resources getSystem() { return SYSTEM; }
    public int getIdentifier(String name, String type, String pkg) {
        return identifiers.containsKey(name) ? identifiers.get(name) : 0;
    }
    public String getString(int id, Object... args) {
        if (!values.containsKey(id)) throw new NotFoundException();
        return String.format(values.get(id), args);
    }
}
