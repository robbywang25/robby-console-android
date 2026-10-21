package wang.robby.console.android.auth;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Process-owned authentication; attach/detach the active Activity in onResume/onPause. */
public final class AuthCoordinator {
    public interface Listener {
        void onAuthChanged();
        void onAuthorizationReady(String url);
        void onAuthError(String message);
        default void onDeviceAuthorization(DeviceAuthorization info) { }
    }
    // Narrow seams let lifecycle/queue ordering be tested without Android storage or networking.
    interface Backend {
        long beginAuthorization();
        String authorizationUrl(long reservation) throws Exception;
        default String federatedUrl(long reservation, String provider) throws Exception { throw new Exception(); }
        void callback(String uri) throws Exception;
        Runnable clearLocal();
        default void cancelAuthorization() { beginAuthorization(); }
        default Runnable cancelDevice(AuthSession.DeviceGrant grant) { cancelAuthorization(); return () -> { }; }
        default AuthSession.DeviceGrant startDevice(long reservation) throws Exception { throw new Exception(); }
        default boolean pollDevice(AuthSession.DeviceGrant grant) throws Exception { throw new Exception(); }
    }
    interface Dispatcher { void post(Runnable runnable); }
    interface Scheduler { void after(Runnable runnable, long millis); }

    private static AuthCoordinator instance;
    private final Object lock = new Object();
    private final AuthSession session;
    private final Backend backend;
    private final Executor executor;
    private final Dispatcher dispatcher;
    private final Scheduler scheduler;
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private long operation, revision, deliveredRevision = -1;
    private boolean busy;
    private String readyUrl, error;
    private boolean deviceActive;
    private AuthSession.DeviceGrant deviceGrant;
    private DeviceAuthorization deliveredDevice;

    public static synchronized AuthCoordinator get(Context context) {
        if (instance == null) {
            AuthSession session = new AuthSession(context.getApplicationContext());
            Handler main = new Handler(Looper.getMainLooper());
            Backend backend = new Backend() {
                @Override public long beginAuthorization() { return session.beginAuthorization(); }
                @Override public String authorizationUrl(long reservation) throws Exception { return session.authorizationUrl(reservation); }
                @Override public String federatedUrl(long reservation, String provider) throws Exception { return session.federatedUrl(reservation, provider); }
                @Override public void callback(String uri) throws Exception { session.handleCallback(Uri.parse(uri)); }
                @Override public Runnable clearLocal() { return session.clearLocal(); }
                @Override public void cancelAuthorization() { session.cancelAuthorization(); }
                @Override public Runnable cancelDevice(AuthSession.DeviceGrant grant) { return session.cancelDeviceAuthorization(grant); }
                @Override public AuthSession.DeviceGrant startDevice(long reservation) throws Exception { return session.startDeviceAuthorization(reservation); }
                @Override public boolean pollDevice(AuthSession.DeviceGrant grant) throws Exception { return session.pollDeviceAuthorization(grant); }
            };
            ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
            instance = new AuthCoordinator(session, backend, worker, main::post,
                    (task, delay) -> worker.schedule(task, delay, TimeUnit.MILLISECONDS));
        }
        return instance;
    }

    AuthCoordinator(AuthSession session, Backend backend, Executor executor, Dispatcher dispatcher) {
        this(session, backend, executor, dispatcher, (task, delay) -> { throw new IllegalStateException(); });
    }
    AuthCoordinator(AuthSession session, Backend backend, Executor executor, Dispatcher dispatcher, Scheduler scheduler) {
        this.session = session; this.backend = backend; this.executor = executor; this.dispatcher = dispatcher; this.scheduler = scheduler;
    }

    /** Always emits a fresh state notification, plus any undelivered result. No Activity is retained strongly. */
    public void attach(Listener next) {
        if (next == null) throw new IllegalArgumentException("登录观察者不能为空。");
        synchronized (lock) { listener = new WeakReference<>(next); deliveredRevision = -1; deliveredDevice = null; }
        notifyChanged();
    }

    public void detach(Listener old) {
        synchronized (lock) { if (listener.get() == old) listener.clear(); }
    }

    public boolean busy() { synchronized (lock) { return busy; } }
    public AuthSession session() { return session; }
    public DeviceAuthorization deviceAuthorization() { synchronized (lock) { return deviceGrant == null ? null : deviceGrant.info; } }

    /** Explicit fallback for approval on a separately authenticated device. */
    public void loginWithDevice() {
        final long expected, reservation;
        synchronized (lock) {
            if (busy) return;
            clearDevice(); busy = true; deviceActive = true; readyUrl = null; error = null;
            expected = ++operation; reservation = backend.beginAuthorization(); revision++;
        }
        notifyChanged();
        executor.execute(() -> {
            if (!current(expected)) return;
            try {
                AuthSession.DeviceGrant grant = backend.startDevice(reservation);
                synchronized (lock) {
                    if (operation != expected) { grant.clear(); return; }
                    deviceGrant = grant; revision++;
                }
                notifyChanged(); schedulePoll(expected);
            } catch (Exception ignored) { finish(expected, null, "无法准备设备登录，请检查网络或稍后重试。"); }
        });
    }

    public void cancelDeviceLogin() {
        final Runnable cleanup;
        synchronized (lock) {
            if (!deviceActive) return;
            operation++; cleanup = backend.cancelDevice(deviceGrant); clearDevice(); busy = false;
            readyUrl = null; error = null; revision++;
        }
        notifyChanged();
        executor.execute(cleanup);
    }

    private void schedulePoll(long expected) {
        try {
            final long delay;
            synchronized (lock) {
                if (operation != expected || deviceGrant == null) return;
                delay = deviceGrant.delay();
            }
            // Scheduled work retains only the coordinator/epoch, never the displayed code.
            scheduler.after(() -> pollDevice(expected), delay);
        } catch (Exception ignored) { finish(expected, null, "设备登录已过期，请重新开始。"); }
    }

    private void pollDevice(long expected) {
        final AuthSession.DeviceGrant grant;
        synchronized (lock) {
            if (operation != expected || deviceGrant == null) return;
            grant = deviceGrant;
        }
        try {
            if (backend.pollDevice(grant)) finish(expected, null, null);
            else schedulePoll(expected);
        } catch (Exception failure) {
            String message = failure.getMessage();
            if (!"已拒绝设备登录。".equals(message) && !"设备登录已过期，请重新开始。".equals(message))
                message = "设备登录未完成，请重新开始。";
            finish(expected, null, message);
        }
    }

    private void clearDevice() {
        if (deviceGrant != null) deviceGrant.clear();
        deviceGrant = null; deliveredDevice = null; deviceActive = false;
    }

    public void login() { login(null); }
    /** {@code provider} null: Passkey through Authelia; "google" or "apple": read-only sign-in with a bound account. */
    public void login(String provider) {
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
                String url = provider == null ? backend.authorizationUrl(reservation) : backend.federatedUrl(reservation, provider);
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
            operation++; clearDevice(); busy = false; readyUrl = null; error = null; revision++;
            revoke = backend.clearLocal();
        }
        notifyChanged();
        executor.execute(revoke);
    }

    private boolean current(long expected) { synchronized (lock) { return expected == operation; } }
    private void finish(long expected, String url, String message) {
        synchronized (lock) {
            if (expected != operation) return;
            clearDevice(); busy = false; readyUrl = url; error = message; revision++;
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
            if (deviceGrant != null && deliveredDevice != deviceGrant.info) {
                deliveredDevice = deviceGrant.info;
                active.onDeviceAuthorization(deviceGrant.info);
            }
            if (listener.get() != active) return;
            if (error != null) {
                String message = error; error = null;
                active.onAuthError(message);
            }
        }
    }
}
