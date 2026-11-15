package org.example.jython3;

import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency, race, self-close, and allowlist enforcement tests for ControlDevice
 * and the Python control module.
 */
class ControlDeviceConcurrencyTest {

    @Test
    @DisplayName("Concurrent writers/readers on SimulatedDevice and ControlModule stay consistent")
    void concurrentDeviceCommandsAndLiveState() throws Exception {
        var dev = new SimulatedDevice("shared-motor");
        int threads = 4;
        int opsPerThread = 100;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        dev.execute("set_level", Map.of("value", PyInt.of(tid * 1000 + i)));
                        var snap = dev.snapshotState();
                        if (!snap.containsKey("level") || !snap.containsKey("power")) {
                            errors.add(new AssertionError("Missing keys in snapshot: " + snap));
                        }
                    }
                } catch (Throwable ex) {
                    errors.add(ex);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertTrue(errors.isEmpty(), "Device race errors: " + errors);
        assertEquals(threads * opsPerThread, dev.commandHistory().size());
    }

    @Test
    @DisplayName("Per-device and per-command allowlists, self-closing listener, and audit counts")
    void commandAllowlistsAndListenerSelfClose() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.EVENTS)
                .allowDevice("dev-a")
                .allowCommand("ping", "set_level")
                .build();

        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps)) {
            var devA = new SimulatedDevice("dev-a");
            var devB = new SimulatedDevice("dev-b");
            interp.controlRegistry().register(devA);
            interp.controlRegistry().register(devB);

            interp.eval("""
                import control
                ctrl = control.Controller("dev-a")
                st = ctrl.state()
                seen = []
                def listener(ev):
                    seen.append((ev.command, st.get("level")))
                    if ev.command == "set_level":
                        ctrl.close()
                ctrl.on_event(listener)
                assert ctrl.send("ping") == "pong:dev-a"
                assert ctrl.send("set_level", {"value": 88}) == 88
                assert seen == [("ping", 0), ("set_level", 88)]
                assert ctrl.closed
                """);

            // Closed controller rejects subsequent sends and state reads
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("ctrl.send('ping')")).typeName());
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("ctrl.state()")).typeName());
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("st.get('level')")).typeName());

            // Unauthorized commands on dev-a and unauthorized dev-b rejected & audited
            interp.eval("ctrl2 = control.Controller('dev-a')");
            for (String badCmd : List.of("reset", "toggle", "power_on", "power_off")) {
                var e = assertThrows(InterpreterException.class,
                        () -> interp.eval("ctrl2.send('" + badCmd + "')"), badCmd);
                assertEquals("SecurityError", e.typeName(), badCmd);
            }
            var devBErr = assertThrows(InterpreterException.class,
                    () -> interp.eval("control.Controller('dev-b')"));
            assertEquals("SecurityError", devBErr.typeName());
            assertTrue(devB.commandHistory().isEmpty());
            assertTrue(interp.hostSession().auditLog().deniedCount() >= 5);
        }
    }
}
