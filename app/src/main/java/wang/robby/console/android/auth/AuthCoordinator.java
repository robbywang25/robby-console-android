package wang.robby.console.android.auth;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Process-owned authentication; attach/detach the active Activity in onResume/onPause. */
public final class AuthCoordinator {
    public interface Listener {
        void onAuthChanged();
        void onAuthorizationReady(String url);
        void onAuthError(String message);
    }
    // Narrow seams let lifecycle/queue ordering be tested without Android storage or networking.
    interface Backend {
        long beginAuthorization();
        String authorizationUrl(long reservation) throws Exception;
        void callback(String uri) throws Exception;
        Runnable clearLocal();
    }
    interface Dispatcher { void post(Runnable runnable); }

    private static AuthCoordinator instance;
    private final Object lock = new Object();
    private final AuthSession session;
    private final Backend backend;
    private final Executor executor;
    private final Dispatcher dispatcher;
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private long operation, revision, deliveredRevision = -1;
    private boolean busy;
    private String readyUrl, error;

    public static synchronized AuthCoordinator get(Context context) {
        if (instance == null) {
            AuthSession session = new AuthSession(context.getApplicationContext());
            Handler main = new Handler(Looper.getMainLooper());
            Backend backend = new Backend() {
                @Override public long beginAuthorization() { return session.beginAuthorization(); }
                @Override public String authorizationUrl(long reservation) throws Exception { return session.authorizationUrl(reservation); }
                @Override public void callback(String uri) throws Exception { session.handleCallback(Uri.parse(uri)); }
                @Override public Runnable clearLocal() { return session.clearLocal(); }
            };
            instance = new AuthCoordinator(session, backend, Executors.newSingleThreadExecutor(), main::post);
        }
        return instance;
    }

    AuthCoordinator(AuthSession session, Backend backend, Executor executor, Dispatcher dispatcher) {
        this.session = session; this.backend = backend; this.executor = executor; this.dispatcher = dispatcher;
    }

    /** Always emits a fresh state notification, plus any undelivered result. No Activity is retained strongly. */
    public void attach(Listener next) {
        if (next == null) throw new IllegalArgumentException("登录观察者不能为空。");
        synchronized (lock) { listener = new WeakReference<>(next); deliveredRevision = -1; }
        notifyChanged();
    }

    public void detach(Listener old) {
        synchronized (lock) { if (listener.get() == old) listener.clear(); }
    }

    public boolean busy() { synchronized (lock) { return busy; } }
    public AuthSession session() { return session; }

    public void login() {
        final long expected, reservation;
        synchronized (lock) {
            if (busy) return;
            busy = true; readyUrl = null; error = null;
            expected = ++operation;
            reservation = backend.beginAuthorization();
            revision++;
        }
        notifyChanged();
        executor.execute(() -> {
            if (!current(expected)) return;
            try {
                String url = backend.authorizationUrl(reservation);
                finish(expected, url, null);
            } catch (Exception ignored) { finish(expected, null, "无法开始登录，请检查网络后重试。"); }
        });
    }

    /** Queues the callback in memory; Activity destruction never cancels this executor. */
    public void callback(Uri uri) {
        if (uri == null) return;
        callbackValue(uri.toString());
    }

    void callbackValue(String uri) {
        final long expected;
        synchronized (lock) {
            // Suppress duplicate callbacks and callbacks superseded by a new login.
            if (busy) return;
            busy = true; readyUrl = null; error = null;
            expected = ++operation; revision++;
        }
        notifyChanged();
        executor.execute(() -> {
            if (!current(expected)) return;
            try { backend.callback(uri); finish(expected, null, null); }
            catch (Exception ignored) { finish(expected, null, "登录未完成或已过期，请重新登录。"); }
        });
    }

    /** Local tokens/pending state are gone before this returns; revocation runs off the UI thread. */
    public void logout() {
        final Runnable revoke;
        synchronized (lock) {
            operation++; busy = false; readyUrl = null; error = null; revision++;
            revoke = backend.clearLocal();
        }
        notifyChanged();
        executor.execute(revoke);
    }

    private boolean current(long expected) { synchronized (lock) { return expected == operation; } }
    private void finish(long expected, String url, String message) {
        synchronized (lock) {
            if (expected != operation) return;
            busy = false; readyUrl = url; error = message; revision++;
        }
        notifyChanged();
    }
    private void notifyChanged() {
        // Never capture a Listener/Activity in posted work.
        dispatcher.post(this::deliver);
    }
    private void deliver() {
        // Production dispatcher is the main looper. Identity is rechecked after
        // onAuthChanged, since it may synchronously detach or replace the Activity.
        synchronized (lock) {
            Listener active = listener.get();
            if (active == null) return;
            if (deliveredRevision != revision) {
                deliveredRevision = revision;
                active.onAuthChanged();
            }
            if (listener.get() != active) return;
            if (readyUrl != null) {
                String url = readyUrl; readyUrl = null;
                active.onAuthorizationReady(url);
            }
            if (listener.get() != active) return;
            if (error != null) {
                String message = error; error = null;
                active.onAuthError(message);
            }
        }
    }
}
