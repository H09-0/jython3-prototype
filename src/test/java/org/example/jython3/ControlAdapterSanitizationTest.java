package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.HostCommandChannelAdapter;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that ControlModule and HostCommandChannelAdapter sanitize all
 * arguments, handler return values, and state snapshots, blocking PyJavaObject
 * leakage and cross-session PyHostHandle smuggling with full audit logging.
 */
class ControlAdapterSanitizationTest {

    @Test
    @DisplayName("Control handler returning PyJavaObject or cross-session PyHostHandle is rejected and audited")
    void controlHandlerResultAndStateSanitized() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.EVENTS)
                .allowDevice("leaky.adapter")
                .allowCommand("leak_java", "leak_nested_java", "leak_handle", "leak_state", "ok")
                .build();

        try (var otherHost = EmbeddedRuntime.builder().uiToolkit(new HeadlessUiToolkit()).build();
             var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build()) {

            PyHostHandle foreignHandle = PyHostHandle.builder("other.Handle")
                    .bindSession(otherHost.hostSession())
                    .prop("x", () -> new PyStr("secret"))
                    .build();

            var adapter = HostCommandChannelAdapter.builder("leaky.adapter")
                    .bindSession(rt.hostSession())
                    .initialState("safe", new PyStr("yes"))
                    .onCommand("ok", (st, args) -> new PyStr("clean"))
                    .onCommand("leak_java", (st, args) -> new PyJavaObject(new File("/etc/passwd"), DenyAllPolicy.INSTANCE))
                    .onCommand("leak_nested_java", (st, args) ->
                            new PyList(List.of(new PyJavaObject(Runtime.getRuntime(), DenyAllPolicy.INSTANCE))))
                    .onCommand("leak_handle", (st, args) -> foreignHandle)
                    .onCommand("leak_state", (st, args) -> {
                        st.put("bad", foreignHandle);
                        return new PyStr("stored");
                    })
                    .build();
            rt.registerDevice(adapter);

            // Clean command succeeds
            rt.eval("import control\nc = control.Controller('leaky.adapter')\nassert c.send('ok') == 'clean'");

            // Direct and nested PyJavaObject return -> SecurityError + audit log
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("c.send('leak_java')")).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("c.send('leak_nested_java')")).typeName());

            // Cross-session PyHostHandle return -> SecurityError + audit log
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("c.send('leak_handle')")).typeName());

            // Polluted state snapshot/get -> SecurityError
            rt.eval("c.send('leak_state')");
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("c.state().get('bad')")).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("c.state().snapshot()")).typeName());

            // HostCommandChannelAdapter.execute direct arg sanitization blocks PyJavaObject
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> adapter.execute("ok", Map.of("raw",
                                    new PyJavaObject(new Object(), DenyAllPolicy.INSTANCE)))).typeName());

            assertTrue(rt.auditEntries().stream()
                    .anyMatch(e -> !e.allowed() && e.action().startsWith("sanitize_")));
        }
    }

    @Test
    @DisplayName("Field test: 100 sanitized control command cycles with allowed handles and primitives")
    void controlSanitizationFieldTest() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.EVENTS)
                .allowDevice("safe.ch")
                .allowCommand("echo")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build()) {
            var ch = HostCommandChannelAdapter.builder("safe.ch")
                    .bindSession(rt.hostSession())
                    .onCommand("echo", (st, args) -> {
                        PyStr v = (PyStr) args.get("msg");
                        st.put("last", v);
                        return v;
                    })
                    .build();
            rt.registerDevice(ch);
            for (int i = 0; i < 100; i++) {
                rt.eval("""
                    import control
                    c = control.Controller("safe.ch")
                    res = c.send("echo", {"msg": "ok-%d"})
                    assert res == "ok-%d" and c.state().get("last") == "ok-%d"
                    """.formatted(i, i, i));
                rt.restart();
            }
            assertEquals(0, rt.hostSession().managedResourceCount());
        }
    }
}
