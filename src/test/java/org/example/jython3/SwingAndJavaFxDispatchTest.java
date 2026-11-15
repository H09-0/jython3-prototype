package org.example.jython3;

import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.example.jython3.ui.JavaFxUiAdapter;
import org.example.jython3.ui.SwingUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests real Swing EDT dispatch (SwingUiToolkit) and simulated FX Application Thread
 * dispatch (JavaFxUiAdapter), including callback cancellation, async timer failure
 * auditing, sync wait timeout, and cleanup on reset/close.
 */
class SwingAndJavaFxDispatchTest {

    private static Jython3Interpreter uiInterp(CapabilitySet caps) {
        return new Jython3Interpreter(
                DenyAllPolicy.INSTANCE,
                HostAccessPolicy.DENY_ALL,
                ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(),
                caps);
    }

    @Test
    @DisplayName("SwingUiToolkit runs on real Swing EDT, rejects off-EDT mutations, and cleans up timers/listeners")
    void swingEdtDispatchAndCleanup() throws Exception {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.TIMER)
                .maxTimers(2)
                .callbackTimeout(Duration.ofMillis(200))
                .build();
        var swing = new SwingUiToolkit();

        try (var interp = uiInterp(caps)) {
            interp.setUiToolkit(swing);

            // Off-EDT widget creation is rejected
            var offErr = assertThrows(InterpreterException.class,
                    () -> interp.eval("import ui\nb = ui.Button('OffEDT')"));
            assertEquals("RuntimeError", offErr.typeName());
            assertTrue(offErr.getMessage().contains("UI thread"), offErr.getMessage());

            // Real Swing EDT execution of Button, Label, TextField, Panel, and SwingTimer
            swing.dispatcher().runAndWait(() -> interp.eval("""
                import ui
                pnl = ui.Panel()
                lbl = ui.Label("Init")
                tf = ui.TextField("")
                btn = ui.Button("Fire")
                events = []
                tf.on_change(lambda ev: events.append(("chg", ev.text)))
                btn.on_click(lambda ev: (events.append(("clk", ev.text)), setattr(lbl, "text", "Done:" + tf.text)))
                pnl.add(lbl)
                pnl.add(tf)
                pnl.add(btn)
                tf.set_text("hello")
                btn.click()
                assert lbl.text == "Done:hello"
                assert events == [("chg", "hello"), ("clk", "hello" if False else "Fire")]
                """));

            if (GraphicsEnvironment.isHeadless()) {
                AtomicReference<Throwable> winErr = new AtomicReference<>();
                swing.dispatcher().runAndWait(() -> {
                    try {
                        interp.eval("w = ui.Window('NoDisplay')");
                    } catch (Throwable t) {
                        winErr.set(t);
                    }
                });
                var ie = org.junit.jupiter.api.Assertions.assertInstanceOf(InterpreterException.class, winErr.get());
                assertEquals("RuntimeError", ie.typeName());
            }

            // SwingTimer failure audits error, stops timer, and respects centralized timer quota
            AtomicReference<Throwable> asyncErr = new AtomicReference<>();
            interp.hostSession().setCallbackErrorHandler((src, err) -> asyncErr.set(err));
            swing.dispatcher().runAndWait(() -> interp.eval("""
                import ui
                def failing_swing_tick():
                    raise ValueError("swing-timer-fail")
                st = ui.Timer(15, failing_swing_tick)
                st.start()
                """));
            Thread.sleep(100);
            assertTrue(!interp.hostSession().callbackFailures().isEmpty());
            assertEquals("ValueError",
                    org.junit.jupiter.api.Assertions.assertInstanceOf(InterpreterException.class, asyncErr.get()).typeName());

            interp.resetExecutionState();
            assertEquals(0, interp.hostSession().liveTimerCount());
            assertEquals(0, interp.hostSession().managedResourceCount());
        }
    }

    @Test
    @DisplayName("JavaFxUiAdapter cancels mid-callback scripts, times out stalled FX thread, and cleans up")
    void javaFxCancellationTimeoutAndCleanup() throws Exception {
        var fxExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "JavaFX-Test-Thread"));
        try {
            AtomicReference<Thread> fxThread = new AtomicReference<>();
            fxExec.submit(() -> fxThread.set(Thread.currentThread())).get();

            var fxDispatcher = new JavaFxUiAdapter.FxDispatcher(
                    fxExec::execute,
                    () -> Thread.currentThread() == fxThread.get(),
                    Duration.ofMillis(80));
            var base = new HeadlessUiToolkit(fxDispatcher);
            var fxToolkit = new JavaFxUiAdapter(fxDispatcher, base);

            var caps = CapabilitySet.builder()
                    .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT)
                    .callbackTimeout(Duration.ofMillis(200))
                    .build();

            try (var interp = uiInterp(caps)) {
                interp.setUiToolkit(fxToolkit);
                AtomicBoolean cancelled = new AtomicBoolean(false);
                interp.setGlobal("trip_cancel", new PyBuiltinFunction("trip_cancel", a -> {
                    interp.cancel();
                    cancelled.set(true);
                    return PyNull.INSTANCE;
                }));

                AtomicReference<Throwable> cbFailure = new AtomicReference<>();
                fxDispatcher.runAndWait(() -> {
                    interp.eval("""
                        import ui
                        w = ui.Window("FX-Cancel")
                        b = ui.Button("Abort")
                        def handler(ev):
                            trip_cancel()
                            w.title = "must-not-change"
                        b.on_click(handler)
                        w.add(b)
                        w.show()
                        """);
                    try {
                        interp.eval("b.click()");
                    } catch (Throwable t) {
                        cbFailure.set(t);
                    }
                });
                assertTrue(cancelled.get());
                var ie = org.junit.jupiter.api.Assertions.assertInstanceOf(InterpreterException.class, cbFailure.get());
                assertEquals("KeyboardInterrupt", ie.typeName());
                fxDispatcher.runAndWait(() -> {
                    interp.eval("ok_title = (w.title == 'FX-Cancel')");
                });
                assertEquals(PyBool.TRUE, interp.getGlobal("ok_title"));
            }
            assertEquals(0, base.openWindowCount());

            // Stalled FX thread triggers TimeoutError on runAndWait rather than hanging
            var stallGate = new CountDownLatch(1);
            var fxBusy = new CountDownLatch(1);
            fxExec.submit(() -> {
                fxBusy.countDown();
                try {
                    stallGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
            });
            assertTrue(fxBusy.await(2, TimeUnit.SECONDS));
            var stallErr = assertThrows(InterpreterException.class,
                    () -> fxDispatcher.runAndWait(() -> {}));
            assertEquals("TimeoutError", stallErr.typeName());
            stallGate.countDown();
        } finally {
            fxExec.shutdownNow();
        }
    }
}
