package org.example.jython3;

import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency, re-entrant teardown, and multi-thousand-cycle leak tests for
 * HostSession, callbacks, windows, timers, and controllers.
 */
class HostConcurrencyAndTeardownTest {

    private static CapabilitySet fullUiAndControlCaps() {
        return CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT,
                        HostCapability.TIMER, HostCapability.CONTROL)
                .allowDevice("dev-1")
                .allowCommand("ping", "set_level")
                .maxWindows(16)
                .maxTimers(16)
                .maxListenersPerNode(32)
                .callbackTimeout(Duration.ofSeconds(1))
                .build();
    }

    private static Jython3Interpreter newHostInterp(CapabilitySet caps) {
        return new Jython3Interpreter(
                DenyAllPolicy.INSTANCE,
                HostAccessPolicy.DENY_ALL,
                ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(),
                caps);
    }

    @Test
    @DisplayName("Concurrent host callbacks from multiple threads serialize cleanly without corruption or ThreadLocal leaks")
    void concurrentCallbacksAreSerializedAndClean() throws Exception {
        try (var interp = newHostInterp(fullUiAndControlCaps())) {
            interp.eval("""
                counter = [0]
                def step(n):
                    counter[0] += n
                    return counter[0]
                """);
            var stepFn = interp.getGlobal("step");

            int threads = 6;
            int callsPerThread = 50;
            var pool = Executors.newFixedThreadPool(threads);
            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(threads);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            interp.hostSession().invokeCallback(stepFn, PyInt.of(1));
                        }
                        if (PyCaller.current() != null) {
                            errors.add(new AssertionError("PyCaller ThreadLocal leaked on worker"));
                        }
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertTrue(doneLatch.await(15, TimeUnit.SECONDS));
            pool.shutdownNow();

            assertTrue(errors.isEmpty(), "Concurrent callback errors: " + errors);
            assertEquals(String.valueOf(threads * callsPerThread),
                    evalAndReadCounter(interp));
        }
    }

    private static String evalAndReadCounter(Jython3Interpreter interp) {
        interp.eval("final_count = counter[0]");
        return interp.getGlobal("final_count").toString();
    }

    @Test
    @DisplayName("resetExecutionState() and close() during active/spinning callbacks abort cleanly with zero deadlock")
    void resetAndCloseDuringCallback() throws Exception {
        // 1. Re-entrant resetExecutionState() triggered from inside a callback
        try (var interp = newHostInterp(fullUiAndControlCaps())) {
            var toolkit = new HeadlessUiToolkit();
            interp.setUiToolkit(toolkit);
            interp.setGlobal("host_reset", new PyBuiltinFunction("host_reset", a -> {
                interp.resetExecutionState();
                return PyNull.INSTANCE;
            }));
            interp.eval("""
                import ui
                w = ui.Window("ResetMe")
                b = ui.Button("Reset")
                b.on_click(lambda ev: host_reset())
                w.add(b)
                w.show()
                """);
            // Resetting mid-eval aborts the active script safely with KeyboardInterrupt
            var abortErr = org.junit.jupiter.api.Assertions.assertThrows(
                    InterpreterException.class, () -> interp.eval("b.click()"));
            assertEquals("KeyboardInterrupt", abortErr.typeName());
            assertEquals(0, toolkit.openWindowCount());
            assertEquals(0, interp.hostSession().managedResourceCount());
            // Interpreter still works cleanly after self-reset
            interp.eval("after = 10 + 20");
            assertEquals("30", interp.getGlobal("after").toString());
        }

        // 2. Concurrent close() from another thread while a spinning callback is executing
        var interp2 = newHostInterp(fullUiAndControlCaps());
        var entered = new CountDownLatch(1);
        interp2.setGlobal("signal_entered", new PyBuiltinFunction("signal_entered", a -> {
            entered.countDown();
            return PyNull.INSTANCE;
        }));
        interp2.eval("""
            def spin():
                signal_entered()
                while True:
                    pass
            """);
        var spinFn = interp2.getGlobal("spin");
        var cbFinished = new CountDownLatch(1);
        var cbError = new ArrayList<Throwable>();
        Thread runner = new Thread(() -> {
            try {
                interp2.hostSession().invokeCallback(spinFn);
            } catch (Throwable t) {
                cbError.add(t);
            } finally {
                cbFinished.countDown();
            }
        });
        runner.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        interp2.close();
        assertTrue(cbFinished.await(2, TimeUnit.SECONDS), "spinning callback must abort promptly on close()");
        long closeMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(closeMs < 2_000, "close took too long: " + closeMs + " ms");
        assertTrue(!cbError.isEmpty() && cbError.get(0) instanceof InterpreterException);
        assertNull(PyCaller.current());
    }

    @Test
    @DisplayName("Closing window or stopping timer inside its own callback is safe and idempotent")
    void closeWindowAndStopTimerInsideOwnCallback() throws Exception {
        try (var toolkit = HeadlessUiToolkit.withDedicatedThread();
             var interp = newHostInterp(fullUiAndControlCaps())) {
            interp.setUiToolkit(toolkit);
            toolkit.dispatcher().runAndWait(() -> interp.eval("""
                import ui
                events = []
                w = ui.Window("SelfClose")
                b = ui.Button("CloseNow")
                def on_click(ev):
                    events.append("click")
                    w.close()
                    w.close()  # second close is a safe no-op
                def on_close(ev):
                    events.append("close")
                    w.close()  # re-entrant close inside on_close must not recurse
                b.on_click(on_click)
                w.on_close(on_close)
                w.add(b)
                w.show()
                b.click()
                assert events == ["click", "close"]
                assert w.closed and not w.visible
                """));
            assertEquals(0, toolkit.openWindowCount());

            AtomicInteger ticks = new AtomicInteger(0);
            interp.setGlobal("record_tick", new PyBuiltinFunction("record_tick", a -> {
                ticks.incrementAndGet();
                return PyNull.INSTANCE;
            }));
            interp.eval("""
                import ui
                t = ui.Timer(10, lambda: (record_tick(), t.stop()))
                t.start()
                """);
            Thread.sleep(80);
            assertEquals(1, ticks.get(), "timer stopped inside its callback must fire only once");
            assertEquals(0, toolkit.activeTimerCount());
        }
    }

    @Test
    @DisplayName("2,000-cycle UI/Control/Timer reset stress test leaks zero threads, resources, or heap")
    void twoThousandCycleTeardownStressNoLeaks() {
        var mem = ManagementFactory.getMemoryMXBean();
        int threadsBefore = Thread.activeCount();
        var caps = fullUiAndControlCaps();

        try (var toolkit = new HeadlessUiToolkit();
             var interp = newHostInterp(caps)) {
            interp.setUiToolkit(toolkit);
            interp.controlRegistry().register(new SimulatedDevice("dev-1"));

            // Warmup JIT
            for (int i = 0; i < 100; i++) {
                runOneCycle(interp);
            }
            System.gc();
            double heapWarm = mem.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);

            for (int i = 0; i < 2_000; i++) {
                runOneCycle(interp);
            }
            System.gc();
            double heapAfter = mem.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
            double deltaMB = heapAfter - heapWarm;

            assertEquals(0, toolkit.openWindowCount());
            assertEquals(0, toolkit.activeTimerCount());
            assertEquals(0, interp.hostSession().openWindowCount());
            assertEquals(0, interp.hostSession().liveTimerCount());
            assertEquals(0, interp.hostSession().managedResourceCount());
            assertTrue(Thread.activeCount() <= threadsBefore + 2,
                    "thread leak: before=" + threadsBefore + " after=" + Thread.activeCount());
            assertTrue(deltaMB < 10.0, "heap grew unexpectedly after 2,000 cycles: +" + deltaMB + " MB");
        }
    }

    private static void runOneCycle(Jython3Interpreter interp) {
        interp.eval("""
            import ui, control
            w = ui.Window("S")
            b = ui.Button("Go")
            lbl = ui.Label("0")
            c = control.Controller("dev-1")
            b.on_click(lambda ev: (c.send("set_level", {"value": 1}), setattr(lbl, "text", "1")))
            w.add(lbl); w.add(b); w.show()
            b.click()
            assert lbl.text == "1"
            """);
        interp.resetExecutionState();
    }
}
