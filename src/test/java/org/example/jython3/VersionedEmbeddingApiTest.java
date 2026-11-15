package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.api.RuntimeVersion;
import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit + field-cycle tests for the versioned public EmbeddedRuntime API.
 */
class VersionedEmbeddingApiTest {

    @Test
    @DisplayName("RuntimeVersion compatibility checks and feature flags enforce contract")
    void versionCompatibilityAndFeatures() {
        var current = RuntimeVersion.CURRENT_API;
        assertTrue(current.isCompatibleWith(new RuntimeVersion(1, 0, 0, "")));
        assertFalse(current.isCompatibleWith(new RuntimeVersion(2, 0, 0, "")));
        assertFalse(current.isCompatibleWith(new RuntimeVersion(1, 99, 0, "")));

        try (var rt = EmbeddedRuntime.builder().build()) {
            rt.requireCompatible(new RuntimeVersion(1, 0, 0, ""));
            assertThrows(IllegalStateException.class,
                    () -> rt.requireCompatible(new RuntimeVersion(2, 0, 0, "")));
            assertTrue(rt.supportsFeature("capability-gating"));
            assertTrue(rt.supportsFeature("live-control-state"));
            assertFalse(rt.supportsFeature("nonexistent-feature"));
            // Default deny-by-default
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import ui\nui.Window('X')")).typeName());
        }
    }

    @Test
    @DisplayName("Field test: EmbeddedRuntime executes 100 full UI+Control+Generator cycles cleanly")
    void embeddedRuntimeMultiCycleFieldRun() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.CONTROL)
                .allowDevice("axis-1")
                .allowCommand("set_level", "ping")
                .build();
        var toolkit = new HeadlessUiToolkit();
        AtomicReference<String> lastHostText = new AtomicReference<>("init");

        try (var rt = EmbeddedRuntime.builder()
                .capabilities(caps)
                .limits(ExecutionLimits.SANDBOXED)
                .uiToolkit(toolkit)
                .build()) {
            rt.registerDevice(new SimulatedDevice("axis-1"));
            for (int cycle = 0; cycle < 100; cycle++) {
                rt.bindModelData("cfg", Map.of("cycle", cycle, "steps", List.of(10, 20)));
                rt.bindHandle("host_view", PyHostHandle.builder("oculix.View")
                        .bindSession(rt.hostSession())
                        .prop("text", () -> new PyStr(lastHostText.get()), v -> lastHostText.set(v.toString()))
                        .method("ping", new PyBuiltinFunction("ping", a -> new PyStr("ok")))
                        .build());

                rt.eval("""
                    import ui, control
                    win = ui.Window("Run-" + str(cfg["cycle"]))
                    lbl = ui.Label("0")
                    btn = ui.Button("Step")
                    ctrl = control.Controller("axis-1")
                    st = ctrl.state()

                    def gen_targets(xs):
                        for x in xs:
                            yield x + cfg["cycle"]

                    it = gen_targets(cfg["steps"])
                    def on_step(ev):
                        val = next(it)
                        ctrl.send("set_level", {"value": val})
                        lbl.text = str(st.get("level"))
                        host_view.text = lbl.text

                    btn.on_click(on_step)
                    win.add(lbl); win.add(btn); win.show()
                    btn.click()
                    btn.click()
                    ok = (lbl.text == str(20 + cfg["cycle"]) and host_view.ping() == "ok")
                    """, "<field-cycle-" + cycle + ">");
                assertEquals(PyBool.TRUE, rt.getGlobal("ok"));
                assertEquals(String.valueOf(20 + cycle), lastHostText.get());
                rt.restart();
            }
            assertEquals(0, toolkit.openWindowCount());
            assertEquals(0, rt.hostSession().managedResourceCount());
            assertTrue(rt.auditEntries().size() > 0);
        }
    }
}
