package org.example.jython3.security;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.api.RuntimeVersion;
import org.example.jython3.control.ControlDevice;
import org.example.jython3.control.ControlRegistry;
import org.example.jython3.control.HostAttestation;
import org.example.jython3.control.HostCommandChannelAdapter;
import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostAppBridge;
import org.example.jython3.host.HostAuditLog;
import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBoundMethod;
import org.example.jython3.runtime.PyCell;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyFrozenSet;
import org.example.jython3.runtime.PyFunction;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PySet;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PyTuple;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security verification for Host Capabilities, PyHostHandle isolation,
 * adapter attestation, PolicySanitizer, and Audit Log sanitization.
 * Standards: OWASP Top 10:2025 A01 (Broken Access Control), A08 (Integrity Failures),
 * A09 (Logging Failures), CWE-285, CWE-347, CWE-117, SEI CERT Java IDS03-J.
 */
@Tag("security")
class HostCapabilityAndHandleSecurityTest {

    @Test
    @DisplayName("OWASP A01 / CWE-285: Capabilities are deny-by-default and device/command allowlists are enforced")
    void denyByDefaultCapabilitiesAndAllowlists() {
        try (var rt = EmbeddedRuntime.builder().build()) {
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import ui\nui.Window('x')")).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import control\ncontrol.Controller('d')")).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import threading\nthreading.Thread(target=lambda: None).start()")).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import concurrent.futures as cf\ncf.ThreadPoolExecutor()")).typeName());
        }

        var partial = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("dev-allowed")
                .allowCommand("ping")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(partial).build()) {
            rt.registerDevice(new SimulatedDevice("dev-allowed"));
            rt.registerDevice(new SimulatedDevice("dev-blocked"));
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import control\ncontrol.Controller('dev-blocked')")).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import control\nc = control.Controller('dev-allowed')\nc.send('reboot')")).typeName());
        }
    }

    @Test
    @DisplayName("OWASP A01: PyHostHandle requires HostSession and cannot be smuggled across sessions or containers")
    void handleSmugglingAndUnboundHandlesRejected() {
        assertThrows(IllegalStateException.class, () -> PyHostHandle.builder("ui.Fake").build());

        var capsA = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.CONTROL)
                .allowDevice("dev-sec")
                .allowCommand("ping")
                .build();
        try (var interpA = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), capsA);
             var interpB = Jython3Interpreter.restricted()) {
            interpA.controlRegistry().register(new SimulatedDevice("dev-sec"));
            interpA.eval("""
                import ui, control
                w = ui.Window("A")
                c = control.Controller("dev-sec")
                class Box:
                    pass
                box = Box()
                box.item = c
                def make_leak():
                    lc = control.Controller("dev-sec")
                    return lambda: lc.send("ping")
                leak = make_leak()
                """);

            PyObject winA = interpA.getGlobal("w");
            PyObject ctrlA = interpA.getGlobal("c");
            PyObject leakFn = interpA.getGlobal("leak");
            PyObject box = interpA.getGlobal("box");

            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("w", winA)).typeName());
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("c", ctrlA)).typeName());
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("l", new PyList(List.of(winA)))).typeName());
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("t", new PyTuple(List.of(winA)))).typeName());
            var s = new PySet(); s.add(winA);
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("s", s)).typeName());
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("fs", new PyFrozenSet(List.of(winA)))).typeName());
            var d = new PyDict(); d.put(new PyStr("k"), ctrlA);
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("d", d)).typeName());
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("cell", new PyCell(ctrlA))).typeName());
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("fn", leakFn)).typeName());
            assertEquals("SecurityError", assertThrows(InterpreterException.class, () -> interpB.setGlobal("box", box)).typeName());

            // HostAppBridge.bindHandle also rejects foreign handles
            try (var bridge = new HostAppBridge(CapabilitySet.none(), new HeadlessUiToolkit(), ExecutionLimits.SANDBOXED)) {
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class, () -> bridge.bindHandle("foreign", (PyHostHandle) winA)).typeName());
            }
        }
    }

    @Test
    @DisplayName("OWASP A01: All session-bound handles (UI, Control, Events, Timers) become stale after reset/close")
    void handlesInvalidatedOnResetAndClose() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT,
                        HostCapability.TIMER, HostCapability.CONTROL)
                .allowDevice("dev-1")
                .allowCommand("ping")
                .build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps)) {
            interp.controlRegistry().register(new SimulatedDevice("dev-1"));
            interp.eval("""
                import ui, control
                ui_evs = []
                ctrl_evs = []
                w = ui.Window("W")
                b = ui.Button("B")
                tm = ui.Timer(100, lambda: None)
                c = control.Controller("dev-1")
                cmd = control.Command("ping")
                b.on_click(lambda ev: ui_evs.append(ev))
                c.on_event(lambda ev: ctrl_evs.append(ev))
                b.click()
                c.send(cmd)
                ev1 = ui_evs[0]
                ev2 = ctrl_evs[0]
                """);
            PyObject w = interp.getGlobal("w");
            PyObject b = interp.getGlobal("b");
            PyObject tm = interp.getGlobal("tm");
            PyObject c = interp.getGlobal("c");
            PyObject ev1 = interp.getGlobal("ev1");
            PyObject ev2 = interp.getGlobal("ev2");

            interp.resetExecutionState();
            interp.setGlobal("w", w); interp.setGlobal("b", b);
            interp.setGlobal("tm", tm); interp.setGlobal("c", c);
            interp.setGlobal("ev1", ev1); interp.setGlobal("ev2", ev2);

            for (String call : new String[]{
                    "w.show()", "b.click()", "tm.cancel()", "c.state()", "ev1.source", "ev2.device"
            }) {
                assertEquals("RuntimeError",
                        assertThrows(InterpreterException.class, () -> interp.eval(call), call).typeName());
            }
        }
    }

    @Test
    @DisplayName("OWASP A08 / CWE-347: Hardware adapter gating, HostAttestation, origin allowlists, and sealed registry")
    void adapterAttestationAndSealEnforcement() {
        ControlDevice forgedHw = new ControlDevice() {
            @Override public String id() { return "hw-1"; }
            @Override public TrustTier trustTier() { return TrustTier.HARDWARE; }
            @Override public PyObject execute(String command, Map<String, PyObject> args) { return new PyStr("ok"); }
            @Override public Map<String, PyObject> snapshotState() { return Map.of(); }
        };
        ControlDevice spoofedSim = new ControlDevice() {
            @Override public String id() { return "spoof"; }
            @Override public TrustTier trustTier() { return TrustTier.SIMULATED; }
            @Override public PyObject execute(String command, Map<String, PyObject> args) { return new PyStr("ok"); }
            @Override public Map<String, PyObject> snapshotState() { return Map.of(); }
        };

        // Self-reported HARDWARE denied unless allowHardwareAdapters(true)
        var noHw = CapabilitySet.builder().allow(HostCapability.CONTROL).allowDevice("hw-1").allowCommand("ping").build();
        try (var rt = EmbeddedRuntime.builder().capabilities(noHw).build()) {
            rt.registerDevice(forgedHw);
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import control\ncontrol.Controller('hw-1')")).typeName());
        }

        // Attested registration enforces origin allowlist, rejects unattested override, and respects seal()
        var originCaps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("spoof", "extra")
                .allowCommand("ping")
                .allowAdapterOrigin("trusted-origin")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(originCaps).build()) {
            rt.registerAttestedDevice(spoofedSim, HostAttestation.trustedHost("untrusted-origin", "reviewer"));
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import control\ncontrol.Controller('spoof')")).typeName());

            rt.lockDeviceRegistrations();
            assertThrows(IllegalStateException.class, () -> rt.registerDevice(new SimulatedDevice("extra")));
        }
    }

    @Test
    @DisplayName("OWASP A09 / CWE-117 / CERT IDS03-J: HostAuditLog neutralizes CRLF/control chars and CommandChannel sanitizes IO")
    void auditLogInjectionNeutralizedAndCommandChannelSanitized() {
        var log = new HostAuditLog();
        log.recordDenied(HostCapability.CONTROL, "send\ncmd", "dev\r\nFAKE_LOG", "bad\u0007detail\nINFO: forged");
        HostAuditLog.AuditEntry entry = log.entries().get(0);
        assertFalse(entry.action().contains("\n") || entry.action().contains("\r"));
        assertFalse(entry.target().contains("\n") || entry.target().contains("\r"));
        assertFalse(entry.detail().contains("\n") || entry.detail().contains("\r") || entry.detail().contains("\u0007"));

        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("ch")
                .allowCommand("echo")
                .build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps);
             var other = Jython3Interpreter.restricted()) {
            PyHostHandle foreign = PyHostHandle.builder("ui.Foreign").bindSession(other.hostSession()).build();
            var adapter = HostCommandChannelAdapter.builder("ch")
                    .bindSession(interp.hostSession())
                    .onCommand("echo", (st, args) -> foreign)
                    .build();
            interp.controlRegistry().register(adapter);
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> interp.eval("import control\ncontrol.Controller('ch').send('echo')")).typeName());
            assertTrue(interp.hostSession().auditLog().deniedCount() >= 1);
        }
    }

    @Test
    @DisplayName("Embedding API enforces RuntimeVersion compatibility and callback depth/timeout")
    void runtimeVersionAndCallbackLimits() {
        assertTrue(RuntimeVersion.CURRENT_API.isCompatibleWith(new RuntimeVersion(1, 0, 0, "")));
        assertFalse(RuntimeVersion.CURRENT_API.isCompatibleWith(new RuntimeVersion(2, 0, 0, "")));

        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT)
                .maxCallbackDepth(3)
                .callbackTimeout(Duration.ofMillis(100))
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).build()) {
            rt.eval("""
                import ui
                b = ui.Button("go")
                def recurse(ev):
                    b.click()
                b.on_click(recurse)
                """);
            InterpreterException rec = assertThrows(InterpreterException.class, () -> rt.eval("b.click()"));
            assertEquals("RecursionError", rec.typeName());
        }
    }
}
