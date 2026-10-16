package android.content;

import java.util.HashMap;
import java.util.Map;

public final class SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    public boolean contains(String key) { return values.containsKey(key); }
    public Editor edit() { return new Editor(); }

    public final class Editor {
        private final Map<String, Object> writes = new HashMap<>();
        public Editor putInt(String key, int value) { writes.put(key, value); return this; }
        public Editor putLong(String key, long value) { writes.put(key, value); return this; }
        public Editor putString(String key, String value) { writes.put(key, value); return this; }
        public Editor putBoolean(String key, boolean value) { writes.put(key, value); return this; }
        public Editor remove(String key) { writes.put(key, null); return this; }
        public boolean commit() {
            writes.forEach((key, value) -> {
                if (value == null) values.remove(key);
                else values.put(key, value);
            });
            return true;
        }
    }
}
