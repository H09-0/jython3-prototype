package org.example.jython3.benchmark;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostAppBridge;
import org.example.jython3.host.HostCapability;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.example.jython3.ui.JavaFxUiAdapter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Benchmarks host capability dispatch overhead: UI button click callbacks,
 * control device command roundtrips, timer create/start/stop churn, and
 * cross-thread FX dispatcher runAndWait throughput.
 */
public final class HostDispatchBenchmark {

    public record HostBenchResult(String name, int ops, double meanMs, double opsPerSec) {}

    public static Map<String, HostBenchResult> runAll() {
        var out = new LinkedHashMap<String, HostBenchResult>();
        out.put("ui_event_dispatch_10k", benchUiEventDispatch(10_000));
        out.put("control_commands_10k", benchControlCommands(10_000));
        out.put("timer_churn_500", benchTimerChurn(500));
        out.put("fx_thread_dispatch_2k", benchFxDispatch(2_000));
        return out;
    }

    private static CapabilitySet allCaps() {
        return CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT,
                        HostCapability.TIMER, HostCapability.CONTROL)
                .allowDevice("bench-dev")
                .allowCommand("set_level", "ping")
                .maxWindows(16)
                .maxTimers(1000)
                .maxListenersPerNode(32)
                .callbackTimeout(Duration.ofSeconds(2))
                .build();
    }

    public static HostBenchResult benchUiEventDispatch(int ops) {
        var toolkit = new HeadlessUiToolkit();
        try (var bridge = new HostAppBridge(allCaps(), toolkit, ExecutionLimits.TRUSTED)) {
            bridge.runScript("""
                import ui
                w = ui.Window("Bench")
                b = ui.Button("Click")
                lbl = ui.Label("0")
                count = [0]
                def on_click(ev):
                    count[0] += 1
                    lbl.text = str(count[0])
                b.on_click(on_click)
                w.add(lbl); w.add(b); w.show()
                """, "<bench-ui-setup>");

            var clickCode = bridge.interpreter().compileSource("for _ in range(" + ops + "):\n    b.click()");
            // Warmup
            bridge.interpreter().evalCode(clickCode, "<bench-ui-warmup>");
            long t0 = System.nanoTime();
            bridge.interpreter().evalCode(clickCode, "<bench-ui>");
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            return new HostBenchResult("ui_event_dispatch_10k", ops, ms, ops / (ms / 1000.0));
        }
    }

    public static HostBenchResult benchControlCommands(int ops) {
        var toolkit = new HeadlessUiToolkit();
        try (var bridge = new HostAppBridge(allCaps(), toolkit, ExecutionLimits.TRUSTED)) {
            bridge.registerDevice(new SimulatedDevice("bench-dev"));
            bridge.runScript("""
                import control
                ctrl = control.Controller("bench-dev")
                st = ctrl.state()
                """, "<bench-ctrl-setup>");
            var cmdCode = bridge.interpreter().compileSource(
                    "for i in range(" + ops + "):\n    ctrl.send('set_level', {'value': i})\n    v = st.get('level')");
            bridge.interpreter().evalCode(cmdCode, "<bench-ctrl-warmup>");
            long t0 = System.nanoTime();
            bridge.interpreter().evalCode(cmdCode, "<bench-ctrl>");
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            return new HostBenchResult("control_commands_10k", ops, ms, ops / (ms / 1000.0));
        }
    }

    public static HostBenchResult benchTimerChurn(int ops) {
        try (var toolkit = HeadlessUiToolkit.withDedicatedThread();
             var bridge = new HostAppBridge(allCaps(), toolkit, ExecutionLimits.TRUSTED)) {
            var churnCode = bridge.interpreter().compileSource(
                    "import ui\n"
                    + "for _ in range(" + ops + "):\n"
                    + "    t = ui.Timer(500, lambda: None)\n"
                    + "    t.start()\n"
                    + "    t.stop()\n");
            bridge.interpreter().evalCode(churnCode, "<bench-timer-warmup>");
            bridge.restart();
            long t0 = System.nanoTime();
            bridge.interpreter().evalCode(churnCode, "<bench-timer>");
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            return new HostBenchResult("timer_churn_500", ops, ms, ops / (ms / 1000.0));
        }
    }

    public static HostBenchResult benchFxDispatch(int ops) {
        var fxExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "bench-fx"));
        try {
            var fxThread = new AtomicReference<Thread>();
            fxExec.submit(() -> fxThread.set(Thread.currentThread())).get();
            var fxDisp = new JavaFxUiAdapter.FxDispatcher(
                    fxExec::execute,
                    () -> Thread.currentThread() == fxThread.get(),
                    Duration.ofSeconds(2));
            var fxToolkit = new JavaFxUiAdapter(fxDisp, new HeadlessUiToolkit(fxDisp));
            try (var bridge = new HostAppBridge(allCaps(), fxToolkit, ExecutionLimits.TRUSTED)) {
                fxDisp.runAndWait(() -> bridge.runScript("""
                    import ui
                    b = ui.Button("FX")
                    n = [0]
                    b.on_click(lambda ev: n.__setitem__(0, n[0] + 1) if False else n.append(1))
                    """, "<bench-fx-setup>"));

                var clickCode = bridge.interpreter().compileSource("b.click()");
                for (int i = 0; i < 200; i++) {
                    fxDisp.runAndWait(() -> bridge.interpreter().evalCode(clickCode, "<bench-fx-warm>"));
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < ops; i++) {
                    fxDisp.runAndWait(() -> bridge.interpreter().evalCode(clickCode, "<bench-fx>"));
                }
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                return new HostBenchResult("fx_thread_dispatch_2k", ops, ms, ops / (ms / 1000.0));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            fxExec.shutdownNow();
        }
    }

    public static void main(String[] args) {
        System.out.println("=========================================================================");
        System.out.println(" JYTHON 3 HOST CAPABILITY & DISPATCH BENCHMARK");
        System.out.println("=========================================================================");
        for (var r : runAll().values()) {
            System.out.printf("%-24s | %7d ops | %8.2f ms | %10.0f ops/sec%n",
                    r.name(), r.ops(), r.meanMs(), r.opsPerSec());
        }
        System.out.println("=========================================================================");
    }
}
