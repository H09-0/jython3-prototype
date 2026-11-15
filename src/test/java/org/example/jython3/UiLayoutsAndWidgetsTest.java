package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.example.jython3.ui.SwingUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for UI layout containers (VBox, HBox, Grid, Panel) and basic widgets
 * (CheckBox, ProgressBar, ListView) across Headless and Swing EDT toolkits.
 */
class UiLayoutsAndWidgetsTest {

    @Test
    @DisplayName("VBox, HBox, Grid, CheckBox, ProgressBar, and ListView enforce validation and events")
    void layoutsAndBasicWidgets() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT)
                .build();
        var toolkit = new HeadlessUiToolkit();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(toolkit).build()) {
            assertTrue(rt.supportsFeature("ui-layouts"));
            assertTrue(rt.supportsFeature("basic-widgets"));

            rt.eval("""
                import ui
                win = ui.Window("Layouts", 800, 600)
                root = ui.VBox(spacing=12)
                bar = ui.HBox(8)
                grid = ui.Grid(columns=3, spacing=4)

                chk = ui.CheckBox("Enable", False)
                prg = ui.ProgressBar(0.25)
                lst = ui.ListView(["alpha", "beta", "gamma"])

                events = []
                chk.on_change(lambda ev: events.append(("chk", ev.text)))
                lst.on_select(lambda ev: events.append(("lst", ev.text)))

                chk.checked = True
                prg.value = 0.75
                lst.selected_index = 1

                bar.add(chk); bar.add(prg)
                grid.add(ui.Label("A")); grid.add(ui.Label("B"))
                root.add(bar); root.add(grid); root.add(lst)
                win.add(root); win.show()

                ok = (
                    root.layout == "vbox" and root.spacing == 12
                    and bar.layout == "hbox" and bar.spacing == 8
                    and grid.layout == "grid" and grid.columns == 3 and grid.spacing == 4
                    and chk.checked == True
                    and prg.value == 0.75
                    and lst.selected_index == 1 and lst.items == ["alpha", "beta", "gamma"]
                    and events == [("chk", "true"), ("lst", "beta")]
                )
                """);
            assertEquals(PyBool.TRUE, rt.getGlobal("ok"));

            // Validation checks
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class, () -> rt.eval("prg.value = 1.5")).typeName());
            assertEquals("IndexError",
                    assertThrows(InterpreterException.class, () -> rt.eval("lst.selected_index = 99")).typeName());
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class, () -> rt.eval("root.layout = 'invalid'")).typeName());
        }
    }

    @Test
    @DisplayName("SwingUiToolkit layouts and widgets execute safely on real Swing EDT")
    void swingEdtLayoutsAndWidgets() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT)
                .build();
        var swing = new SwingUiToolkit();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(swing).build()) {
            AtomicReference<Throwable> err = new AtomicReference<>();
            swing.dispatcher().runAndWait(() -> {
                try {
                    rt.eval("""
                        import ui
                        box = ui.VBox(6)
                        g = ui.Grid(2, 4)
                        c = ui.CheckBox("Sync", False)
                        p = ui.ProgressBar(0.5, False)
                        l = ui.ListView(["one", "two"])
                        seen = []
                        c.on_change(lambda e: seen.append(e.text))
                        l.on_select(lambda e: seen.append(e.text))
                        c.checked = True
                        l.selected_index = 1
                        p.indeterminate = True
                        g.add(c); g.add(p)
                        box.add(g); box.add(l)
                        ok = (seen == ["true", "two"] and p.indeterminate and g.columns == 2)
                        """);
                } catch (Throwable t) {
                    err.set(t);
                }
            });
            if (err.get() != null) throw new AssertionError(err.get());
            assertEquals(PyBool.TRUE, rt.getGlobal("ok"));
            rt.restart();
            assertEquals(0, rt.hostSession().managedResourceCount());
        }
    }

    @Test
    @DisplayName("Field test: 150 cycles of layout + widget trees and event teardown")
    void layoutsAndWidgetsFieldTest() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT)
                .build();
        var toolkit = new HeadlessUiToolkit();
        try (var rt = EmbeddedRuntime.builder()
                .capabilities(caps)
                .limits(ExecutionLimits.SANDBOXED)
                .uiToolkit(toolkit)
                .build()) {
            for (int i = 0; i < 150; i++) {
                rt.eval("""
                    import ui
                    w = ui.Window("Dash")
                    v = ui.VBox(4)
                    g = ui.Grid(2, 2)
                    c = ui.CheckBox("Auto", False)
                    p = ui.ProgressBar(0.0)
                    l = ui.ListView(["x", "y", "z"])
                    c.on_change(lambda ev: setattr(p, "value", 1.0) if ev.text == "true" else None)
                    l.on_select(lambda ev: setattr(c, "checked", True))
                    g.add(c); g.add(p); v.add(g); v.add(l); w.add(v); w.show()
                    l.selected_index = 2
                    assert p.value == 1.0 and c.checked
                    """);
                rt.restart();
            }
            assertEquals(0, toolkit.openWindowCount());
            assertEquals(0, rt.hostSession().managedResourceCount());
        }
    }
}
