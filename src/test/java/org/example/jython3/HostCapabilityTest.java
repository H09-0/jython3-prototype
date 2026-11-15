package org.example.jython3;

import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the deny-by-default capability layer, audit log, opaque
 * PyHostHandle isolation, callback limits, and interpreter close/reset teardown.
 */
class HostCapabilityTest {

    @Test
    @DisplayName("Capabilities are deny-by-default and all checks are recorded in HostAuditLog")
    void denyByDefaultAndAuditLog() {
        try (var interp = Jython3Interpreter.restricted()) {
            var session = interp.hostSession();
            for (HostCapability cap : HostCapability.values()) {
                assertFalse(session.capabilities().has(cap), "default must deny " + cap);
                var e = assertThrows(InterpreterException.class,
                        () -> session.require(cap, "open", "res"));
                assertEquals("SecurityError", e.typeName());
            }
            assertEquals(HostCapability.values().length, session.auditLog().deniedCount());
            assertEquals(0, session.auditLog().allowedCount());
        }
    }

    @Test
    @DisplayName("Explicitly granted capabilities and control scopes enforce device/command allowlists")
    void explicitGrantsAndControlScopes() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.CONTROL)
                .allowDevice("servo-1")
                .allowCommand("set_angle")
                .build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps)) {
            var session = interp.hostSession();
            session.require(HostCapability.UI, "create_window", "Main");
            session.requireControlCommand("servo-1", "set_angle");

            assertThrows(InterpreterException.class, () -> session.require(HostCapability.NETWORK, "connect", "localhost"));
            assertThrows(InterpreterException.class, () -> session.requireControlCommand("servo-2", "set_angle"));
            assertThrows(InterpreterException.class, () -> session.requireControlCommand("servo-1", "flash_firmware"));

            assertEquals(2, session.auditLog().allowedCount());
            assertEquals(3, session.auditLog().deniedCount());
        }
    }

    @Test
    @DisplayName("PyHostHandle exposes only whitelisted members with zero Java reflection")
    void hostHandleIsReflectionFree() {
        try (var interp = Jython3Interpreter.restricted()) {
            final String[] label = {"init"};
            PyHostHandle handle = PyHostHandle.builder("ui.MockWidget")
                    .prop("text", () -> new PyStr(label[0]), v -> label[0] = v.toString())
                    .method("ping", new PyBuiltinFunction("ping", args -> PyInt.of(42)))
                    .build();
            interp.setGlobal("w", handle);
            interp.eval("""
                assert w.text == "init"
                w.text = "updated"
                assert w.text == "updated"
                assert w.ping() == 42
                assert hasattr(w, "text") and hasattr(w, "ping")
                assert not hasattr(w, "getClass")
                assert "ping" in dir(w) and "text" in dir(w)
                ok = True
                """);
            assertEquals(PyBool.TRUE, interp.getGlobal("ok"));

            var ae = assertThrows(InterpreterException.class, () -> interp.eval("w.getClass()"));
            assertEquals("AttributeError", ae.typeName());
        }
    }

    @Test
    @DisplayName("HostSession closes managed resources on reset/close and bounds callback depth & timeout")
    void lifecycleCleanupAndCallbackLimits() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.EVENTS)
                .maxCallbackDepth(3)
                .callbackTimeout(Duration.ofMillis(40))
                .build();
        var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps);

        AtomicInteger closedCount = new AtomicInteger();
        interp.hostSession().registerResource(closedCount::incrementAndGet);
        interp.hostSession().registerResource(closedCount::incrementAndGet);
        assertEquals(2, interp.hostSession().managedResourceCount());

        interp.resetExecutionState();
        assertEquals(2, closedCount.get());
        assertEquals(0, interp.hostSession().managedResourceCount());

        // Callback recursion limit enforced
        interp.eval("""
            def rec_cb(n):
                return trigger_cb(n + 1)
            """);
        var recFn = interp.getGlobal("rec_cb");
        interp.setGlobal("trigger_cb", new PyBuiltinFunction("trigger_cb",
                args -> interp.hostSession().invokeCallback(recFn, args)));
        var recErr = assertThrows(InterpreterException.class,
                () -> interp.hostSession().invokeCallback(recFn, PyInt.of(0)));
        assertEquals("RecursionError", recErr.typeName());
        assertNull(PyCaller.current());

        // Callback timeout enforced when invoked outside eval()
        interp.eval("def spin():\n    while True:\n        pass");
        var spinFn = interp.getGlobal("spin");
        var timeoutErr = assertThrows(InterpreterException.class,
                () -> interp.hostSession().invokeCallback(spinFn));
        assertEquals("TimeoutError", timeoutErr.typeName());
        assertNull(PyCaller.current());

        // Callback timeout also enforced INSIDE eval() and interrupts blocking time.sleep() promptly
        interp.eval("""
            import time
            def sleeper():
                time.sleep(5)
            """);
        interp.setGlobal("invoke_sleeper", new PyBuiltinFunction("invoke_sleeper",
                a -> interp.hostSession().invokeCallback(interp.getGlobal("sleeper"))));
        long t0 = System.nanoTime();
        var sleepTimeout = assertThrows(InterpreterException.class, () -> interp.eval("invoke_sleeper()"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals("TimeoutError", sleepTimeout.typeName());
        assertTrue(elapsedMs < 1_000, "blocking callback must abort promptly, took " + elapsedMs + " ms");
        assertFalse(Thread.currentThread().isInterrupted(), "watchdog interrupt must be cleared after callback");
        assertNull(PyCaller.current());

        // Closing interpreter closes session and prevents further eval/callbacks
        interp.hostSession().registerResource(closedCount::incrementAndGet);
        interp.close();
        assertEquals(3, closedCount.get());
        assertTrue(interp.isClosed());
        assertThrows(InterpreterException.class, () -> interp.eval("x = 1"));
        assertThrows(InterpreterException.class, () -> interp.hostSession().invokeCallback(spinFn));
    }
}
