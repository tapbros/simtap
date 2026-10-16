package android.database;

import android.os.Handler;

public abstract class ContentObserver {
    public ContentObserver(Handler handler) {}
    public void onChange(boolean self) {}
}
