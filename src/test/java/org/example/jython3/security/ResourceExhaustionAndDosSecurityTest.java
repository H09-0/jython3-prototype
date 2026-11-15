package org.example.jython3.security;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security verification against Resource Exhaustion, Algorithmic Complexity (ReDoS),
 * Integer Wraparound, and Uncontrolled Recursion.
 * Standards: OWASP Top 10:2025 A06 (Insecure Design) & A10 (Exceptional Conditions),
 * MITRE CWE-400, CWE-770, CWE-1333, CWE-190, CWE-674, CERT MSC05-J, OpenSSF pyscg-0002.
 */
@Tag("security")
class ResourceExhaustionAndDosSecurityTest {

    @Test
    @DisplayName("CWE-190 / CWE-400 / OpenSSF pyscg-0002: BigInt wraparound on sequence multiplication and bitLength bombs blocked")
    void integerWraparoundAndBitLengthBombsBlocked() {
        var tight = new ExecutionLimits(100_000, Duration.ofSeconds(2), 50_000, 1_000, 1_000, 500);
        try (var rt = EmbeddedRuntime.builder().limits(tight).build()) {
            // BigInt > 2^64 wraparound on str * BigInt, list * BigInt, tuple * BigInt
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("'a' * ((1 << 64) + 5)")).typeName());
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("[1] * ((1 << 64) + 5)")).typeName());
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("(1,) * ((1 << 64) + 5)")).typeName());
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("b'a' * ((1 << 64) + 5)")).typeName());

            // BitLength amplification via power and repeated squaring
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("(1 << 10000) ** 500")).typeName());
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("(1 << 100000) << 500")).typeName());
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        x = 1 << 10000
                        for _ in range(10):
                            x = x * x
                        """)).typeName());

            // Base * 0 remains safe and empty
            rt.eval("z = 'a' * 0\nzb = 'a' * (-(1 << 64))");
            assertEquals("", rt.getGlobal("z").toString());
            assertEquals("", rt.getGlobal("zb").toString());
        }
    }

    @Test
    @DisplayName("CWE-400 / CERT MSC05-J: Memory amplification across format, %, str, bytes, JSON, and ReDoS/re.sub blocked")
    void stringFormatJsonAndRegexAmplificationBlocked() {
        var tight = new ExecutionLimits(100_000, Duration.ofSeconds(2), 50_000, 1_000, 1_000, 500);
        try (var rt = EmbeddedRuntime.builder().limits(tight).build()) {
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("'%999999999f' % 1.0")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("('%s' * 50) % tuple(['x' * 50] * 50)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("format('x', '999999999')")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("('{0}' * 50).format('A' * 50)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("('a' * 200).replace('a', 'b' * 20)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("('x' * 50).join(['a'] * 50)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("'x'.center(5000)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("bytes(5000)")).typeName());

            // JSON amplification & digit cap
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import json\njson.dumps(['A' * 400, 'B' * 400, 'C' * 400])")).typeName());
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import json\njson.loads('[' + ','.join(['1'] * 1200) + ']')")).typeName());

            // Regex expansion bomb in re.sub / re.findall
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import re\nre.sub('', 'A' * 50, 'x' * 100)")).typeName());
        }

        var smallColl = new ExecutionLimits(100_000, Duration.ofSeconds(2), 50_000, 200, 1_000, 500);
        try (var rt = EmbeddedRuntime.builder().limits(smallColl).build()) {
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import re\nre.findall('a', 'a' * 500)")).typeName());
        }

        // CWE-1333 ReDoS catastrophic backtracking & JSON integer digit cap under SANDBOXED limits
        try (var rt = EmbeddedRuntime.builder().limits(ExecutionLimits.SANDBOXED).build()) {
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import json\njson.loads('1' * 15000)")).typeName());
        }
        var redosLimits = new ExecutionLimits(50_000, Duration.ofMillis(150), 50_000, 1_000, 10_000, 500);
        try (var rt = EmbeddedRuntime.builder().limits(redosLimits).build()) {
            InterpreterException err = assertThrows(InterpreterException.class,
                    () -> rt.eval("import re\nre.search('(.*a){15}b', 'a' * 30)"));
            assertEquals("TimeoutError", err.typeName());
        }
    }

    @Test
    @DisplayName("CWE-400 / CWE-834: Lazy range & builtins (sum, zip, list, all, any) cannot bypass ExecutionLimits")
    void builtinsAndLazyIteratorsEnforceLimits() {
        var tight = new ExecutionLimits(10_000, Duration.ofMillis(300), 50_000, 500, 1_000, 500);
        try (var rt = EmbeddedRuntime.builder().limits(tight).build()) {
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("list(range(0, 2_000_000_000))")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("a, *b = range(0, 2_000_000_000)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("[*range(0, 2_000_000_000)]")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import itertools\nlist(itertools.count())")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("zip(range(1000), range(1000))")).typeName());
            assertEquals("TimeoutError",
                    assertThrows(InterpreterException.class, () -> rt.eval("sum(range(10_000_000_000))")).typeName());
            assertEquals("TimeoutError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import itertools\nall(itertools.repeat(True))")).typeName());
            assertEquals("TimeoutError",
                    assertThrows(InterpreterException.class, () -> rt.eval("import itertools\nany(itertools.repeat(False))")).typeName());
        }
    }

    @Test
    @DisplayName("CWE-674: Cyclic containers, comparisons, __str__, and class __bases__ raise safe Python errors, never StackOverflowError")
    void cyclicStructuresAndRecursionSafety() {
        try (var rt = EmbeddedRuntime.builder().build()) {
            // Cyclic list & dict str() match CPython [[...]] / {1: {...}} without StackOverflowError
            rt.eval("a = []\na.append(a)\nsa = str(a)\nd = {}\nd[1] = d\nsd = str(d)");
            assertEquals("[[...]]", rt.getGlobal("sa").toString());
            assertEquals("{1: {...}}", rt.getGlobal("sd").toString());

            // Cyclic equality comparison -> RecursionError (bounded)
            assertEquals("RecursionError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        x = []; x.append(x)
                        y = []; y.append(y)
                        x == y
                        """)).typeName());

            // Infinite __str__ recursion -> RecursionError
            assertEquals("RecursionError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        class LoopStr:
                            def __str__(self):
                                return str(self)
                        str(LoopStr())
                        """)).typeName());

            // Cyclic __bases__ -> TypeError
            assertEquals("TypeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        class A: pass
                        class B(A): pass
                        A.__bases__ = (B,)
                        """)).typeName());
        }
    }
}
