package android.content;

import java.util.HashMap;
import java.util.Map;

public class Context {
    public static final int MODE_PRIVATE = 0;
    private static final Map<String, SharedPreferences> prefs = new HashMap<>();
    private final ContentResolver resolver = new ContentResolver();
    private final android.content.pm.PackageManager packages = new android.content.pm.PackageManager();
    public android.content.pm.PackageManager getPackageManager() { return packages; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs.computeIfAbsent(name, ignored -> new SharedPreferences());
    }
    public ContentResolver getContentResolver() { return resolver; }
    public String getString(int id, Object... args) { return Integer.toString(id); }
}
