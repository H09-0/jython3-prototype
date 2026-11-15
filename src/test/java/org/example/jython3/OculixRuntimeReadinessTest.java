package org.example.jython3;

import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OculixRuntimeReadinessTest {

    public static class SampleSettings {
        public static boolean actionLogs = false;
        public static double minSimilarity = 0.7;
        public static final int CONSTANT_VAL = 42;
    }

    public static class SampleTarget {
        private final String label;
        public SampleTarget(String label) { this.label = label; }
        public String greet(String who) { return label + ":" + who; }
    }

    private static final JavaAccessPolicy TEST_JAVA_POLICY = new JavaAccessPolicy() {
        @Override public boolean canAccessClass(String name) {
            return name.startsWith("org.example.jython3.OculixRuntimeReadinessTest")
                    || AllowlistPolicy.trusted().canAccessClass(name);
        }
        @Override public boolean canConstructClass(String name, String desc) { return canAccessClass(name); }
        @Override public boolean canCallMethod(String owner, String method, String desc) { return canAccessClass(owner); }
        @Override public boolean canReadField(String owner, String field) { return canAccessClass(owner); }
        @Override public boolean canWriteField(String owner, String field) { return canAccessClass(owner); }
    };

    @Test
    void stdoutAndStderrRedirectionRoutesPrintAndSysStreams() {
        var outBuf = new ByteArrayOutputStream();
        var errBuf = new ByteArrayOutputStream();
        try (var interp = new Jython3Interpreter(TEST_JAVA_POLICY, HostAccessPolicy.PERMISSIVE, ExecutionLimits.TRUSTED)) {
            interp.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            interp.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            interp.eval("""
                    import sys
                    print("hello", "oculix")
                    sys.stdout.write("via-sys\\n")
                    sys.stderr.write("err-sys\\n")
                    """);
            interp.exec("1 / 0");
        }
        String outStr = outBuf.toString(StandardCharsets.UTF_8);
        String errStr = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(outStr.contains("hello oculix"), outStr);
        assertTrue(outStr.contains("via-sys"), outStr);
        assertTrue(errStr.contains("err-sys"), errStr);
        assertTrue(errStr.contains("ZeroDivisionError"), errStr);
    }

    @Test
    void javaClassStaticFieldWriteAndImportStarWork() {
        SampleSettings.actionLogs = false;
        SampleSettings.minSimilarity = 0.7;
        try (var interp = new Jython3Interpreter(TEST_JAVA_POLICY, HostAccessPolicy.PERMISSIVE, ExecutionLimits.TRUSTED)) {
            interp.eval("""
                    from org.example.jython3.OculixRuntimeReadinessTest import SampleSettings
                    from org.example.jython3.OculixRuntimeReadinessTest.SampleSettings import *
                    SampleSettings.actionLogs = True
                    SampleSettings.minSimilarity = 0.9
                    c = CONSTANT_VAL
                    """);
            assertTrue(SampleSettings.actionLogs);
            assertEquals(0.9, SampleSettings.minSimilarity, 1e-6);
            assertEquals(PyInt.of(42), interp.getGlobal("c"));
        }
    }

    @Test
    void getattrDelegationDirOnJavaObjectAndGlobalsWork() {
        try (var interp = new Jython3Interpreter(TEST_JAVA_POLICY, HostAccessPolicy.PERMISSIVE, ExecutionLimits.TRUSTED)) {
            interp.eval("""
                    from org.example.jython3.OculixRuntimeReadinessTest import SampleTarget
                    class Wrapper:
                        def __init__(self, target):
                            self._target = target
                        def __getattr__(self, name):
                            return getattr(self._target, name)

                    raw = SampleTarget("OK")
                    w = Wrapper(raw)
                    res = w.greet("Jython3")
                    members = dir(raw)
                    has_greet = "greet" in members
                    g = globals()
                    has_res = "res" in g
                    """);
            assertEquals(new PyStr("OK:Jython3"), interp.getGlobal("res"));
            assertEquals(true, ((org.example.jython3.runtime.PyBool) interp.getGlobal("has_greet")).value());
            assertEquals(true, ((org.example.jython3.runtime.PyBool) interp.getGlobal("has_res")).value());
        }
    }

    @Test
    void sikuliBundleImportReloadListenerAndEvalFileWork(@TempDir Path tempDir) throws Exception {
        Path bundle = tempDir.resolve("helper.sikuli");
        Files.createDirectories(bundle);
        Path helperPy = bundle.resolve("helper.py");
        Files.writeString(helperPy, "VALUE = 10\n", StandardCharsets.UTF_8);

        Path mainPy = tempDir.resolve("main.py");
        Files.writeString(mainPy, """
                import sys, importlib, helper
                v1 = helper.VALUE
                arg1 = sys.argv[1]
                """, StandardCharsets.UTF_8);

        List<Path> imported = new ArrayList<>();
        try (var interp = new Jython3Interpreter(TEST_JAVA_POLICY, HostAccessPolicy.PERMISSIVE, ExecutionLimits.TRUSTED)) {
            interp.addModulePath(tempDir);
            interp.addImportListener(imported::add);
            interp.setArgv(List.of(mainPy.toString(), "run-arg"));
            interp.evalFile(mainPy);

            assertEquals(PyInt.of(10), interp.getGlobal("v1"));
            assertEquals(new PyStr("run-arg"), interp.getGlobal("arg1"));
            assertTrue(imported.stream().anyMatch(p -> p.endsWith("helper.py")));

            // Modify helper.py and reload via importlib.reload
            Files.writeString(helperPy, "VALUE = 99\n", StandardCharsets.UTF_8);
            interp.eval("import importlib, helper\nimportlib.reload(helper)\nv2 = helper.VALUE\n");
            assertEquals(PyInt.of(99), interp.getGlobal("v2"));
        }
    }
}
