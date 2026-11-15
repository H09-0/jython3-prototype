package org.example.jython3;

import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Phase 3: ReDoS-hardened re module (Match/Pattern/sub/split/flags),
 * json indent/sort_keys/control-escape/depth limits, format(), and f-string specs.
 */
class StdlibAndFormatHardeningTest {

    @Test
    @DisplayName("re.search/match/fullmatch/compile/sub/split/flags match CPython")
    void reModuleCompletenessMatchesCPython() {
        String code = """
            import re
            m = re.search(r"(\\w+)=(\\d+)", "user=42 age=99")
            print(m.group(0), m.group(1), m.group(2), m.groups(), m.span(), m.start(2), m.end(2))
            m2 = re.match(r"user", "user=42")
            print(m2 is not None, re.match(r"42", "user=42") is None)
            print(re.fullmatch(r"\\d+", "12345").group(), re.fullmatch(r"\\d+", "123a") is None)
            p = re.compile(r"abc", re.IGNORECASE)
            print(p.search("xxAbCyy").group(), p.findall("ABC abc"))
            print(re.sub(r"\\d+", "X", "a1 b2 c3", 2))
            print(re.split(r"[,;]+", "a,b;;c,d", 2))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Catastrophic ReDoS pattern is interrupted cleanly by ExecutionLimits")
    void redosInterruptedByExecutionLimits() {
        var limits = new ExecutionLimits(10_000, Duration.ofMillis(150), 50_000, 10_000, 10_000, 100);
        try (var py = new Jython3Interpreter(AllowlistPolicy.trusted(), HostAccessPolicy.DENY_ALL, limits)) {
            InterpreterException ex = assertThrows(InterpreterException.class, () -> py.eval("""
                import re
                re.search(r"(.*a){18}z", "a" * 25 + "!")
                """));
            assertTrue("TimeoutError".equals(ex.typeName()) || "MemoryError".equals(ex.typeName()),
                    "Expected TimeoutError or MemoryError, got: " + ex.typeName());
        }
    }

    @Test
    @DisplayName("json.dumps indent, sort_keys, control chars, and depth limits work and round-trip")
    void jsonDumpsOptionsAndHardening() {
        String code = """
            import json
            obj = {"b": "line1\\nline2\\t\\"ok\\"", "a": [1, True, None]}
            s = json.dumps(obj, sort_keys=True)
            roundtrip = json.loads(s)
            print(roundtrip["b"], roundtrip["a"])
            pretty = json.dumps({"z": 1, "a": 2}, indent=2, sort_keys=True)
            print(pretty)
            """;
        DifferentialHarness.assertMatchesCPython(code);

        try (var py = new Jython3Interpreter()) {
            InterpreterException deepErr = assertThrows(InterpreterException.class, () -> py.eval("""
                import json
                x = []
                cur = x
                for _ in range(600):
                    nxt = []
                    cur.append(nxt)
                    cur = nxt
                json.dumps(x)
                """));
            assertEquals("RecursionError", deepErr.typeName());
        }
    }

    @Test
    @DisplayName("format() and f-string format specifiers & conversions match CPython and block width DoS")
    void formatAndFStringsMatchCPython() {
        String code = """
            x = 3.14159
            n = 42
            name = "hi"
            print(f"{x:.2f} | {n:04x} | {n:#x} | {n:>5d} | {name!r} | {name:*^6}")
            print(format(1234567, ",d"), format(0.256, ".1%"), format(-7, "+05d"))
            d = {"k": 9}
            print(f"val={d['k']:03d} slice={[1, 2, 3][0:2]}")
            """;
        DifferentialHarness.assertMatchesCPython(code);

        try (var py = new Jython3Interpreter()) {
            InterpreterException widthErr = assertThrows(InterpreterException.class,
                    () -> py.eval("f\"{'a':1000000000}\""));
            assertEquals("MemoryError", widthErr.typeName());
        }
    }
}
