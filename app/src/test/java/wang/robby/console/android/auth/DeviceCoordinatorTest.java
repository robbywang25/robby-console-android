package wang.robby.console.android.auth;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceCoordinatorTest {
    static final class Queue implements Executor, AuthCoordinator.Dispatcher, AuthCoordinator.Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        long delay;
        public void execute(Runnable task) { tasks.add(task); }
        public void post(Runnable task) { tasks.add(task); }
        public void after(Runnable task, long delay) { this.delay = delay; tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
    static final class Backend implements AuthCoordinator.Backend {
        long epoch; int starts, polls, revoked; boolean session;
        Runnable duringStart, duringPoll; boolean denied;
        public long beginAuthorization() { return ++epoch; }
        public String authorizationUrl(long reservation) { return "https://example.test/authorize"; }
        public void callback(String ignored) { }
        public Runnable clearLocal() { epoch++; session = false; return () -> revoked++; }
        public AuthSession.DeviceGrant startDevice(long reservation) throws Exception {
            starts++; if (duringStart != null) duringStart.run();
            if (reservation != epoch) throw new Exception();
            DeviceAuthorization info = new DeviceAuthorization(AuthSession.ISSUER + "/consent/openid/device-authorization", "TEST-CODE", System.currentTimeMillis() + 60000, 5);
            return new AuthSession.DeviceGrant(info, "synthetic-private-code", reservation, null,
                    new DevicePollPolicy(60, 5, System.nanoTime() / 1_000_000L), () -> System.nanoTime() / 1_000_000L);
        }
        public boolean pollDevice(AuthSession.DeviceGrant grant) throws Exception {
            polls++; if (duringPoll != null) duringPoll.run();
            if (epoch != grant.reservation) { revoked++; throw new Exception(); }
            if (denied) throw new Exception("已拒绝设备登录。");
            session = true; return true;
        }
    }
    static final class Observer implements AuthCoordinator.Listener {
        int info, errors, changes, urls;
        public void onAuthChanged() { changes++; }
        public void onAuthorizationReady(String url) { urls++; }
        public void onAuthError(String error) { errors++; }
        public void onDeviceAuthorization(DeviceAuthorization authorization) { info++; }
    }
    static final class Fixture {
        final Queue worker = new Queue(), main = new Queue(), scheduler = new Queue();
        final Backend backend = new Backend();
        final AuthCoordinator coordinator = new AuthCoordinator(null, backend, worker, main, scheduler);
    }
    @Test public void deviceInfoOnlyDeliveredToAttachedForegroundOwnerAndReplaysAfterRotation() {
        Fixture f = new Fixture(); Observer old = new Observer(), fresh = new Observer();
        f.coordinator.attach(old); f.coordinator.loginWithDevice(); f.coordinator.detach(old);
        f.worker.drain(); f.main.drain(); assertEquals(0, old.info);
        f.coordinator.attach(fresh); f.main.drain(); assertEquals(1, fresh.info);
        assertTrue(f.scheduler.delay > 0 && f.scheduler.delay <= 5000);
        f.coordinator.detach(fresh); f.coordinator.attach(fresh); f.main.drain(); assertEquals(2, fresh.info);
        f.scheduler.drain(); f.main.drain(); assertNull(f.coordinator.deviceAuthorization()); assertFalse(f.coordinator.busy());
    }
    @Test public void cancelQueuedStartNeverExposesCodeAndAllowsPkceRetry() {
        Fixture f = new Fixture(); Observer observer = new Observer(); f.coordinator.attach(observer);
        f.coordinator.loginWithDevice(); f.coordinator.cancelDeviceLogin(); f.worker.drain(); f.main.drain();
        assertEquals(0, f.backend.starts); assertEquals(0, observer.info); assertFalse(f.coordinator.busy());
        f.coordinator.login(); f.worker.drain(); f.main.drain(); assertEquals(1, observer.urls);
    }
    @Test public void cancelRemovesInfoAndScheduledPollCannotRunAgainstNextGeneration() {
        Fixture f = new Fixture(); f.coordinator.loginWithDevice(); f.worker.drain();
        assertNotNull(f.coordinator.deviceAuthorization()); long prior = f.backend.epoch;
        f.coordinator.cancelDeviceLogin(); assertTrue(f.backend.epoch > prior);
        f.scheduler.drain(); f.worker.drain(); assertNull(f.coordinator.deviceAuthorization()); assertEquals(0, f.backend.polls);
    }
    @Test public void logoutDuringPollPreventsLateSessionAndSuppressesOldError() {
        Fixture f = new Fixture(); Observer observer = new Observer(); f.coordinator.attach(observer);
        f.coordinator.loginWithDevice(); f.worker.drain(); f.main.drain();
        f.backend.duringPoll = () -> f.coordinator.logout(); f.scheduler.drain(); f.worker.drain(); f.main.drain();
        assertFalse(f.backend.session); assertTrue(f.backend.revoked > 0); assertEquals(0, observer.errors); assertNull(f.coordinator.deviceAuthorization());
    }
    @Test public void denialTerminatesAndDoesNotAutomaticallyRestart() {
        Fixture f = new Fixture(); Observer observer = new Observer(); f.coordinator.attach(observer);
        f.backend.denied = true; f.coordinator.loginWithDevice(); f.worker.drain(); f.scheduler.drain(); f.main.drain();
        assertEquals(1, observer.errors); assertEquals(1, f.backend.starts); assertEquals(1, f.backend.polls);
        assertFalse(f.coordinator.busy()); assertNull(f.coordinator.deviceAuthorization()); assertTrue(f.scheduler.tasks.isEmpty());
    }
    @Test public void cancellationDuringInitialRequestDiscardsItsLateResponse() {
        Fixture f = new Fixture(); Observer observer = new Observer(); f.coordinator.attach(observer);
        f.backend.duringStart = () -> f.coordinator.cancelDeviceLogin();
        f.coordinator.loginWithDevice(); f.worker.drain(); f.main.drain();
        assertEquals(0, observer.info); assertEquals(0, observer.errors); assertNull(f.coordinator.deviceAuthorization());
    }
}
