package wang.robby.console.android;

import android.app.Activity;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import wang.robby.console.android.auth.NativePasskeyFlow;
import wang.robby.console.android.auth.SystemPasskey;

/**
 * Native Passkey sign-in for MainActivity: the HTTP ceremony runs on its own worker, while the system Passkey sheet and
 * the consent prompt run on the main thread and the worker waits for them. It belongs to one Activity and is cancelled
 * with it; nothing is resumed later, the person simply signs in again.
 */
final class NativeLogin implements NativePasskeyFlow.Ui {
    interface Host {
        /** Asks whether God may access the workbench; answers exactly once. */
        void consent(Consumer<Boolean> answer);
        /** The callback URL to hand to the coordinator, or the failure; on the main thread, only for the current run. */
        void finished(String callback, NativePasskeyFlow.Failure failure);
    }
    private final Activity activity;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile NativePasskeyFlow flow;
    private volatile CancellationSignal signal;
    private volatile CompletableFuture<?> waiting;

    NativeLogin(Activity activity, Host host) { this.activity = activity; this.host = host; }

    /** Android 14+ with the platform Credential Manager; older systems keep the Auth Tab sign-in. */
    static boolean supported(Activity activity) { return Build.VERSION.SDK_INT >= 34 && SystemPasskey.available(activity); }
    boolean running() { return flow != null; }

    void start(String authorizationUrl) {
        if (flow != null || Build.VERSION.SDK_INT < 34) return;
        final NativePasskeyFlow run;
        try { run = new NativePasskeyFlow(this, SystemPasskey.origin(activity)); }
        catch (Exception e) { host.finished(null, NativePasskeyFlow.failure(SystemPasskey.UNAVAILABLE)); return; }
        flow = run;
        worker.execute(() -> {
            String callback = null; NativePasskeyFlow.Failure failure = null;
            try { callback = run.run(authorizationUrl); } catch (NativePasskeyFlow.Failure f) { failure = f; }
            final String url = callback; final NativePasskeyFlow.Failure error = failure;
            main.post(() -> { if (flow != run) return; flow = null; host.finished(url, error); });
        });
    }

    /** Stops the current run: the system sheet, a pending prompt and the next request. Safe to call repeatedly. */
    void cancel() {
        NativePasskeyFlow run = flow; flow = null;
        if (run != null) run.cancel();
        CancellationSignal s = signal; signal = null; if (s != null) s.cancel();
        CompletableFuture<?> w = waiting; waiting = null; if (w != null) w.completeExceptionally(NativePasskeyFlow.cancelled());
    }
    void dispose() { cancel(); worker.shutdownNow(); }

    @Override public String assertion(String requestJson) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>(); waiting = result;
        main.post(() -> {
            if (flow == null || activity.isDestroyed() || Build.VERSION.SDK_INT < 34) { result.completeExceptionally(NativePasskeyFlow.cancelled()); return; }
            CancellationSignal s = new CancellationSignal(); signal = s;
            SystemPasskey.get(activity, requestJson, s, result);
        });
        try { return await(result); } finally { signal = null; }
    }

    @Override public boolean consent() throws Exception {
        CompletableFuture<Boolean> answer = new CompletableFuture<>(); waiting = answer;
        main.post(() -> {
            if (flow == null || activity.isDestroyed()) { answer.completeExceptionally(NativePasskeyFlow.cancelled()); return; }
            host.consent(answer::complete);
        });
        return await(answer);
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        try { return future.get(); }
        catch (ExecutionException e) { throw e.getCause() instanceof Exception ? (Exception) e.getCause() : NativePasskeyFlow.cancelled(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw NativePasskeyFlow.cancelled(); }
        finally { waiting = null; }
    }
}
