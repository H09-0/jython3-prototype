package org.example.jython3.logic;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.api.RuntimeVersion;
import org.example.jython3.benchmark.HostDispatchBenchmark;
import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.compile.BytecodeDisassembler;
import org.example.jython3.compile.BytecodeValidator;
import org.example.jython3.compile.CodeBuilder;
import org.example.jython3.compile.OpCode;
import org.example.jython3.control.HostAttestation;
import org.example.jython3.control.HostCommandChannelAdapter;
import org.example.jython3.control.JvmTelemetryAdapter;
import org.example.jython3.control.ScopedWorkspaceAdapter;
import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostAppBridge;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.example.jython3.ui.JavaFxUiAdapter;
import org.example.jython3.ui.SwingUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logic & integration tests for EmbeddedRuntime, HostAppBridge, UI Toolkits/Layouts/Widgets,
 * Control Adapters, JavaBridge interoperability, Compiler/BytecodeValidator/Disassembler,
 * and benchmark smoke checks.
 */
@Tag("logic")
class EmbeddingUiControlBridgeAndCompilerTest {

    public static class BeanHost {
        public static int staticVal = 10;
        private String label = "init";
        public int count = 1;

        public String getLabel() { return label; }
        public void setLabel(String s) { this.label = s; }
        public String join(String sep, String... parts) { return String.join(sep, parts); }
        public int sumArr(int[] arr) {
            int s = 0;
            for (int v : arr) s += v;
            return s;
        }
    }

    @Test
    @DisplayName("EmbeddedRuntime, HostAppBridge, UI Layouts/Widgets, and Swing/JavaFX dispatchers work end-to-end")
    void embeddedRuntimeUiAndBridgeLifecycle() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.TIMER)
                .build();
        var headless = new HeadlessUiToolkit();
        try (var rt = EmbeddedRuntime.builder()
                .capabilities(caps)
                .uiToolkit(headless)
                .limits(ExecutionLimits.SANDBOXED)
                .build()) {
            assertTrue(rt.supportsFeature("capability-gating"));
            assertTrue(rt.supportsFeature("lazy-generators"));
            assertEquals(RuntimeVersion.CURRENT_API, rt.apiVersion());

            rt.bindModelData("cfg", Map.of("title", "Oculix", "items", List.of("a", "b")));
            PyHostHandle model = PyHostHandle.builder("oculix.Project")
                    .bindSession(rt.hostSession())
                    .prop("name", () -> new PyStr("Demo"))
                    .method("ping", new org.example.jython3.runtime.PyBuiltinFunction("ping", a -> new PyStr("pong")))
                    .build();
            rt.bindHandle("project", model);

            rt.eval("""
                import ui
                clicks = []
                w = ui.Window(cfg["title"])
                vb = ui.VBox(8)
                hb = ui.HBox(4)
                gr = ui.Grid(2, 6)
                cb = ui.CheckBox("Auto", True)
                pb = ui.ProgressBar()
                pb.value = 0.65
                lv = ui.ListView(cfg["items"])
                btn = ui.Button("Go")
                btn.on_click(lambda ev: clicks.append((ev.type, project.name, project.ping())))
                hb.add(btn); vb.add(hb); vb.add(cb); gr.add(pb); gr.add(lv)
                w.add(vb)
                w.show()
                btn.click()
                """);
            assertEquals("[('button.click', 'Demo', 'pong')]", rt.getGlobal("clicks").toString());
        }

        // SwingUiToolkit & JavaFxUiAdapter dispatcher abstractions
        var swing = new SwingUiToolkit();
        assertNotNull(swing.dispatcher());

        var fxLogs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var fxOnThread = new JavaFxUiAdapter(
                new JavaFxUiAdapter.FxDispatcher(r -> { fxLogs.add("fx"); r.run(); }, () -> true),
                new HeadlessUiToolkit());
        var fxSession = new org.example.jython3.host.HostSession(
                CapabilitySet.of(HostCapability.UI), (c, a) -> org.example.jython3.runtime.PyNull.INSTANCE);
        var w = fxOnThread.createWindow(fxSession, "FX", 300, 200);
        w.show();

        var fxOffThread = new JavaFxUiAdapter.FxDispatcher(r -> { fxLogs.add("fx"); r.run(); }, () -> false);
        fxOffThread.runAndWait(() -> fxLogs.add("ran"));
        fxSession.close();
        assertTrue(fxLogs.contains("fx") && fxLogs.contains("ran"));
    }

    @Test
    @DisplayName("Control adapters (JvmTelemetry, HostCommandChannel, ScopedWorkspace) & HostAppBridge operate cleanly")
    void realControlAdaptersAndHostAppBridge(@TempDir Path tmp) {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM, HostCapability.UI, HostCapability.EVENTS)
                .allowDevice("telemetry", "channel", "ws", "sim")
                .allowCommand("sample", "echo", "write_text", "read_text", "list_dir", "exists", "ping")
                .build();
        try (var bridge = new HostAppBridge(caps, new HeadlessUiToolkit(), ExecutionLimits.SANDBOXED)) {
            var session = bridge.interpreter().hostSession();
            bridge.registerAttestedDevice(new JvmTelemetryAdapter("telemetry"),
                    HostAttestation.trustedHost("host", "reviewer"));
            bridge.registerDevice(HostCommandChannelAdapter.builder("channel")
                    .bindSession(session)
                    .initialState("mode", new PyStr("ready"))
                    .onCommand("echo", (st, args) -> args.getOrDefault("msg", new PyStr("")))
                    .build());
            bridge.registerDevice(new ScopedWorkspaceAdapter("ws", tmp, session, false));
            bridge.registerDevice(new SimulatedDevice("sim"));

            bridge.runScript("""
                import control
                tel = control.Controller("telemetry").send("sample")
                ch = control.Controller("channel")
                echoed = ch.send("echo", {"msg": "hi"})
                ws = control.Controller("ws")
                ws.send("write_text", {"path": "sub/hello.txt", "content": "world"})
                txt = ws.send("read_text", {"path": "sub/hello.txt"})
                listing = ws.send("list_dir", {"path": "sub"})
                ex = ws.send("exists", {"path": "sub/hello.txt"})
                sim = control.Controller("sim").send("ping")
                """, "<adapters>");

            assertEquals("hi", bridge.getGlobal("echoed").toString());
            assertEquals("world", bridge.getGlobal("txt").toString());
            assertEquals("['hello.txt']", bridge.getGlobal("listing").toString());
            assertEquals("True", bridge.getGlobal("ex").toString());
            assertEquals("pong:sim", bridge.getGlobal("sim").toString());
        }
    }

    @Test
    @DisplayName("JavaBridge constructors, methods, bean accessors, varargs, arrays, SAM proxies, and static writes work")
    void javaBridgeLogic() {
        var policy = new org.example.jython3.security.JavaAccessPolicy() {
            @Override public boolean canAccessClass(String n) {
                return BeanHost.class.getName().equals(n) || AllowlistPolicy.trusted().canAccessClass(n);
            }
            @Override public boolean canConstructClass(String n, String d) {
                return BeanHost.class.getName().equals(n) || AllowlistPolicy.trusted().canConstructClass(n, d);
            }
            @Override public boolean canCallMethod(String o, String m, String d) {
                return BeanHost.class.getName().equals(o) || AllowlistPolicy.trusted().canCallMethod(o, m, d);
            }
            @Override public boolean canReadField(String o, String f) { return canAccessClass(o); }
            @Override public boolean canWriteField(String o, String f) {
                return BeanHost.class.getName().equals(o) && !"class".equals(f);
            }
        };
        var interp = new Jython3Interpreter(policy);
        interp.setGlobal("BeanHost", JavaBridge.resolveClassOrPackage(BeanHost.class.getName(), policy));
        interp.eval("""
            from java.util import ArrayList, Collections
            bh = BeanHost()
            bh.label = "updated"
            BeanHost.staticVal = 55
            bh.count = 9
            joined = bh.join("-", "x", "y")
            arr_sum = bh.sumArr([1, 2, 3, 4])
            xs = ArrayList(["pear", "fig", "apple"])
            Collections.sort(xs, lambda a, b: len(a) - len(b))
            res = (bh.label, BeanHost.staticVal, bh.count, joined, arr_sum, xs.get(0))
            """);
        assertEquals("('updated', 55, 9, 'x-y', 10, 'fig')", interp.getGlobal("res").toString());
    }

    @Test
    @DisplayName("BytecodeValidator, BytecodeDisassembler, Parser edge cases, and BenchmarkSmoke work")
    void compilerValidatorDisassemblerAndBenchmark() {
        var cb = new CodeBuilder();
        cb.emit(OpCode.LOAD_CONST, 1);
        cb.emit(OpCode.RETURN_VALUE);
        var cc = cb.build();
        BytecodeValidator.validate(cc);
        String dis = BytecodeDisassembler.disassemble(cc, "<test>");
        assertTrue(dis.contains("LOAD_CONST") && dis.contains("RETURN_VALUE"));

        // Invalid jump target fails BytecodeValidator
        var badCb = new CodeBuilder();
        badCb.emitRaw(OpCode.JUMP, 999);
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(badCb.build()));

        // Parser edge cases (trailing commas, deep parens, multiline strings, comments)
        var py = Jython3Interpreter.restricted();
        py.eval("t = (1, 2,)\nl = [1,\n2,\n]\nms = '''a\nb'''");
        assertEquals("(1, 2)", py.getGlobal("t").toString());
        assertEquals("a\nb", py.getGlobal("ms").toString());

        // HostDispatchBenchmark smoke check
        var bench = HostDispatchBenchmark.benchUiEventDispatch(50);
        assertNotNull(bench);
        assertTrue(bench.ops() == 50 && bench.opsPerSec() > 0);
    }
}
