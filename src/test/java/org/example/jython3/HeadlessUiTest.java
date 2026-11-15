package org.example.jython3;

import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.example.jython3.ui.JavaFxUiAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless UI tests covering window/widget creation, click/text/close events,
 * event ordering, wrong-thread rejection, FX thread-safe dispatch, callback
 * timeouts and exceptions, timer/listener cleanup, and capability denial.
 */
class HeadlessUiTest {

    private static Jython3Interpreter uiInterp(CapabilitySet caps, ExecutionLimits limits) {
        return new Jython3Interpreter(
                DenyAllPolicy.INSTANCE,
                HostAccessPolicy.DENY_ALL,
                limits,
                ExceptionTypeRegistry.standard(),
                caps);
    }

    @Test
    @DisplayName("Headless UI lifecycle: Window, Button, Label, TextField, Panel, events, ordering, and close cleanup")
    void headlessWindowWidgetsAndEventOrdering() {
        var caps = CapabilitySet.of(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT);
        var toolkit = new HeadlessUiToolkit();
        try (var interp = uiInterp(caps, ExecutionLimits.SANDBOXED)) {
            interp.setUiToolkit(toolkit);
            interp.eval("""
                import ui
                log = []
                win = ui.Window("Demo", width=320, height=200)
                pnl = ui.Panel()
                lbl = ui.Label("Idle")
                txt = ui.TextField("")
                btn = ui.Button("Run")

                def on_btn(ev):
                    log.append(("click", ev.type, ev.text))
                    lbl.text = "Running:" + txt.text

                def on_txt(ev):
                    log.append(("change", ev.type, ev.text))

                def on_win_close(ev):
                    log.append(("close", ev.type, ev.text))

                btn.on_click(on_btn)
                txt.on_change(on_txt)
                win.on_close(on_win_close)

                pnl.add(lbl)
                pnl.add(txt)
                pnl.add(btn)
                win.add(pnl)
                win.show()

                assert win.visible and not win.closed
                assert win.title == "Demo" and win.width == 320 and win.height == 200

                txt.set_text("job1")
                btn.click()
                assert lbl.text == "Running:job1"

                # Disabled button ignores click
                btn.enabled = False
                btn.click()
                assert len(log) == 2

                win.close()
                assert win.closed and not win.visible
                assert log == [
                    ("change", "textfield.change", "job1"),
                    ("click", "button.click", "Run"),
                    ("close", "window.close", "Demo"),
                ]
                ok = True
                """);
            assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
            assertEquals(0, toolkit.openWindowCount());
            assertTrue(toolkit.eventHistory().size() >= 4);

            // Mutating a closed window raises RuntimeError
            var closedErr = assertThrows(InterpreterException.class, () -> interp.eval("win.show()"));
            assertEquals("RuntimeError", closedErr.typeName());
        }
        assertNull(PyCaller.current());
    }

    @Test
    @DisplayName("UI callbacks enforce timeout, surface exceptions cleanly, and respect thread affinity")
    void callbackTimeoutExceptionAndThreadAffinity() throws Exception {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT)
                .callbackTimeout(Duration.ofMillis(40))
                .build();
        var toolkit = new HeadlessUiToolkit();
        try (var interp = uiInterp(caps, ExecutionLimits.SANDBOXED)) {
            interp.setUiToolkit(toolkit);

            // 1. Callback exception propagates cleanly without breaking subsequent UI actions
            interp.eval("""
                import ui
                btn = ui.Button("Boom")
                def bad_click(ev):
                    raise ValueError("bad-ui-handler")
                btn.on_click(bad_click)
                """);
            var err = assertThrows(InterpreterException.class, () -> interp.eval("btn.click()"));
            assertEquals("ValueError", err.typeName());
            assertNull(PyCaller.current());

            // 2. Infinite loop in callback stops via TimeoutError
            interp.eval("""
                btn2 = ui.Button("Spin")
                def spin_click(ev):
                    while True:
                        pass
                btn2.on_click(spin_click)
                """);
            var tErr = assertThrows(InterpreterException.class, () -> interp.eval("btn2.click()"));
            assertEquals("TimeoutError", tErr.typeName());
            assertNull(PyCaller.current());

            // 3. Off-thread UI mutation is rejected
            interp.eval("lbl = ui.Label('safe')");
            AtomicReference<Throwable> offThreadErr = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    interp.eval("lbl.text = 'illegal-thread'");
                } catch (Throwable t) {
                    offThreadErr.set(t);
                }
            });
            other.start();
            other.join(5_000);
            var ie = org.junit.jupiter.api.Assertions.assertInstanceOf(InterpreterException.class, offThreadErr.get());
            assertEquals("RuntimeError", ie.typeName());
            assertTrue(ie.getMessage().contains("UI thread"), ie.getMessage());
        }
    }

    @Test
    @DisplayName("JavaFxUiAdapter dispatches safely onto simulated FX Application Thread and rejects wrong thread")
    void javaFxAdapterDispatchAndThreadSafety() {
        var fxExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "JavaFX-App-Thread"));
        try {
            AtomicReference<Thread> fxThreadRef = new AtomicReference<>();
            fxExec.submit(() -> fxThreadRef.set(Thread.currentThread())).get();

            var fxDispatcher = new JavaFxUiAdapter.FxDispatcher(
                    fxExec::execute,
                    () -> Thread.currentThread() == fxThreadRef.get(),
                    Duration.ofSeconds(2));
            var baseToolkit = new HeadlessUiToolkit(fxDispatcher);
            var fxToolkit = new JavaFxUiAdapter(fxDispatcher, baseToolkit);

            var caps = CapabilitySet.of(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT);
            try (var interp = uiInterp(caps, ExecutionLimits.SANDBOXED)) {
                interp.setUiToolkit(fxToolkit);

                // Calling from non-FX thread fails thread-affinity check
                var offErr = assertThrows(InterpreterException.class,
                        () -> interp.eval("import ui\nw = ui.Window('Off')"));
                assertEquals("RuntimeError", offErr.typeName());

                // Dispatching onto FX thread succeeds
                fxDispatcher.runAndWait(() -> {
                    interp.eval("""
                        import ui
                        w = ui.Window("FX")
                        b = ui.Button("Go")
                        hits = []
                        b.on_click(lambda ev: hits.append(ev.text))
                        w.add(b)
                        w.show()
                        b.click()
                        assert hits == ["Go"]
                        """);
                });
                assertEquals(1, baseToolkit.openWindowCount());
            }
            // Closing interpreter closes FX window automatically
            assertEquals(0, baseToolkit.openWindowCount());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            fxExec.shutdownNow();
        }
    }

    @Test
    @DisplayName("Timers fire callbacks and stop cleanly on interpreter reset/close; unauthorized UI actions denied")
    void timersAndPolicyDenials() throws Exception {
        var timerCaps = CapabilitySet.of(HostCapability.TIMER);
        try (var toolkit = HeadlessUiToolkit.withDedicatedThread();
             var interp = uiInterp(timerCaps, ExecutionLimits.SANDBOXED)) {
            interp.setUiToolkit(toolkit);
            interp.eval("""
                import ui
                ticks = []
                t = ui.Timer(15, lambda: ticks.append(1))
                t.start()
                """);
            Thread.sleep(90);
            assertEquals(1, toolkit.activeTimerCount());
            assertTrue(interp.getGlobal("ticks").toString().contains("1"), "timer must tick on dedicated UI thread");
            interp.resetExecutionState();
            assertEquals(0, toolkit.activeTimerCount());
            assertNull(interp.getGlobal("ticks"));

            // Failing async timer callback stops timer, audits error, and notifies host error handler
            AtomicReference<Throwable> seenAsyncErr = new AtomicReference<>();
            interp.hostSession().setCallbackErrorHandler((src, err) -> seenAsyncErr.set(err));
            interp.eval("""
                import ui
                def bad_tick():
                    raise ValueError("timer-boom")
                t_bad = ui.Timer(10, bad_tick)
                t_bad.start()
                """);
            Thread.sleep(80);
            assertEquals(0, toolkit.activeTimerCount(), "failing timer must stop itself");
            var recorded = interp.hostSession().callbackFailures();
            assertEquals(1, recorded.size());
            assertEquals("ValueError",
                    org.junit.jupiter.api.Assertions.assertInstanceOf(InterpreterException.class, recorded.get(0).error()).typeName());
            assertEquals(recorded.get(0).error(), seenAsyncErr.get());
            assertTrue(interp.hostSession().auditLog().entries().stream()
                    .anyMatch(e -> !e.allowed() && "async_callback_error".equals(e.action())));
        }

        // Creator-owned HeadlessDispatcher cannot have its thread ownership hijacked from another thread
        var creatorToolkit = new HeadlessUiToolkit();
        AtomicReference<Throwable> crossRunErr = new AtomicReference<>();
        Thread intruder = new Thread(() -> {
            try {
                creatorToolkit.dispatcher().runAndWait(() -> {});
            } catch (Throwable t) {
                crossRunErr.set(t);
            }
        });
        intruder.start();
        intruder.join(5_000);
        assertEquals("RuntimeError",
                org.junit.jupiter.api.Assertions.assertInstanceOf(InterpreterException.class, crossRunErr.get()).typeName());

        // Denied without UI / EVENTS / INPUT / TIMER
        try (var denied = uiInterp(CapabilitySet.none(), ExecutionLimits.SANDBOXED)) {
            denied.eval("import ui");
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> denied.eval("ui.Window('No')")).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> denied.eval("ui.Timer(10, lambda: None)")).typeName());
        }
        try (var uiOnly = uiInterp(CapabilitySet.of(HostCapability.UI), ExecutionLimits.SANDBOXED)) {
            uiOnly.eval("import ui\nb = ui.Button('NoInput')");
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> uiOnly.eval("b.on_click(lambda e: None)")).typeName());
        }
    }
}
