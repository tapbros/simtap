package android.os;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Handler {
    private final Map<Runnable, Long> pending = new LinkedHashMap<>();
    public Handler(Looper looper) {}
    public boolean postDelayed(Runnable runnable, long delay) {
        pending.put(runnable, SystemClock.now + delay);
        return true;
    }
    public void removeCallbacks(Runnable runnable) { pending.remove(runnable); }
    public int pendingCount() { return pending.size(); }
    public void runDue(long now) {
        SystemClock.now = now;
        // Snapshot, with cancellation checked before invocation, like a serial event queue.
        for (Map.Entry<Runnable, Long> entry : new LinkedHashMap<>(pending).entrySet()) {
            if (entry.getValue() <= now && pending.containsKey(entry.getKey())) {
                pending.remove(entry.getKey());
                entry.getKey().run();
            }
        }
    }
}
