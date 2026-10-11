package wang.robby.console.android.auth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthCoordinatorTest {
    private static final class Queue implements Executor, AuthCoordinator.Dispatcher {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void post(Runnable task) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
    private static final class Backend implements AuthCoordinator.Backend {
        int authorizations, callbacks, cleared, revoked;
        long generation;
        boolean session;
        Runnable duringAuthorization;
        @Override public long beginAuthorization() { return ++generation; }
        @Override public String authorizationUrl(long reservation) throws Exception {
            authorizations++;
            if (duringAuthorization != null) duringAuthorization.run();
            if (reservation != generation) throw new Exception();
            return "https://auth.example/authorize?state=test";
        }
        @Override public void callback(String uri) { callbacks++; session = true; }
        @Override public Runnable clearLocal() { cleared++; generation++; session = false; return () -> revoked++; }
    }
    private static class Observer implements AuthCoordinator.Listener {
        int changes;
        final List<String> urls = new ArrayList<>(), errors = new ArrayList<>();
        @Override public void onAuthChanged() { changes++; }
        @Override public void onAuthorizationReady(String url) { urls.add(url); }
        @Override public void onAuthError(String message) { errors.add(message); }
    }
    private static final class Fixture {
        final Queue worker = new Queue(), main = new Queue();
        final Backend backend = new Backend();
        final AuthCoordinator coordinator = new AuthCoordinator(null, backend, worker, main);
    }
    @Test public void rotatedActivityGetsPreparedAuthorizationExactlyOnce() {
        Fixture f = new Fixture(); Observer old = new Observer(), fresh = new Observer();
        f.coordinator.attach(old); f.main.drain();
        f.coordinator.login(); f.coordinator.detach(old);
        f.worker.drain(); f.main.drain();
        assertTrue(old.urls.isEmpty()); assertFalse(f.coordinator.busy());
        f.coordinator.attach(fresh); f.main.drain();
        assertEquals(1, fresh.urls.size());
        f.coordinator.detach(fresh); f.coordinator.attach(fresh); f.main.drain();
        assertEquals(1, fresh.urls.size());
    }
    @Test public void queuedDeliveryChecksCurrentListenerIdentity() {
        Fixture f = new Fixture(); Observer old = new Observer(), fresh = new Observer();
        f.coordinator.attach(old); f.coordinator.login(); f.worker.drain();
        f.coordinator.detach(old); f.coordinator.attach(fresh); f.main.drain();
        assertEquals(0, old.changes); assertTrue(old.urls.isEmpty());
        assertEquals(1, fresh.urls.size());
    }
    @Test public void logoutClearsImmediatelyAndCancelsUnstartedAuthorization() {
        Fixture f = new Fixture(); Observer observer = new Observer();
        f.backend.session = true;
        f.coordinator.attach(observer); f.coordinator.login(); f.coordinator.logout();
        assertFalse(f.backend.session); assertEquals(1, f.backend.cleared);
        assertEquals(0, f.backend.revoked); assertFalse(f.coordinator.busy());
        f.worker.drain(); f.main.drain();
        assertEquals(0, f.backend.authorizations); assertEquals(1, f.backend.revoked);
        assertTrue(observer.urls.isEmpty()); assertTrue(observer.errors.isEmpty());
    }
    @Test public void logoutDuringAuthorizationDiscardsOldCompletionAndAllowsFreshLogin() {
        Fixture f = new Fixture(); Observer observer = new Observer();
        f.coordinator.attach(observer);
        f.backend.duringAuthorization = () -> f.coordinator.logout();
        f.coordinator.login(); f.worker.drain(); f.main.drain();
        assertFalse(f.coordinator.busy()); assertTrue(observer.urls.isEmpty()); assertTrue(observer.errors.isEmpty());
        f.backend.duringAuthorization = null;
        f.coordinator.login(); f.worker.drain(); f.main.drain();
        assertEquals(1, observer.urls.size());
    }
    @Test public void callbackSurvivesDetachAndReconcilesOnAttach() {
        Fixture f = new Fixture(); Observer old = new Observer(), fresh = new Observer();
        f.coordinator.attach(old); f.main.drain();
        f.coordinator.callbackValue("https://callback.example?code=memory-only");
        f.coordinator.callbackValue("https://callback.example?code=duplicate");
        f.coordinator.detach(old); f.worker.drain(); f.main.drain();
        assertEquals(1, f.backend.callbacks); assertTrue(f.backend.session); assertFalse(f.coordinator.busy());
        f.coordinator.attach(fresh); f.main.drain();
        assertEquals(1, fresh.changes); assertEquals(1, old.changes);
    }
    @Test public void logoutCancelsQueuedCallback() {
        Fixture f = new Fixture();
        f.coordinator.callbackValue("https://callback.example?code=memory-only"); f.coordinator.logout();
        f.worker.drain(); f.main.drain();
        assertEquals(0, f.backend.callbacks); assertFalse(f.backend.session);
    }
    @Test public void detachingDuringStateNotificationPreservesAuthorizationForNextActivity() {
        Fixture f = new Fixture();
        Observer old = new Observer() { @Override public void onAuthChanged() { f.coordinator.detach(this); } };
        Observer fresh = new Observer();
        f.coordinator.login(); f.worker.drain(); f.coordinator.attach(old); f.main.drain();
        assertTrue(old.urls.isEmpty());
        f.coordinator.attach(fresh); f.main.drain(); assertEquals(1, fresh.urls.size());
    }
}
