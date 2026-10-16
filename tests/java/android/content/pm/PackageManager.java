package android.content.pm;
import android.content.res.Resources;

public final class PackageManager {
    public static final class NameNotFoundException extends Exception {}
    public final Resources resources = new Resources();
    public boolean unavailable;
    public PackageManager() {
        resources.put("mobile_data_disable_title", "Turn off mobile data?");
        resources.put("mobile_data_disable_message", "No internet with %1$s.");
        resources.put("mobile_data_disable_message_default_carrier", "your carrier");
    }
    public Resources getResourcesForApplication(String pkg) throws NameNotFoundException {
        if (unavailable) throw new NameNotFoundException();
        return resources;
    }
}
