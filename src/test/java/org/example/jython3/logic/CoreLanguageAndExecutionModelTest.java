package org.example.jython3.logic;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logic & conformance tests for Python 3 core language, operators, Execution Model §4.2.2,
 * closures, PEP 3110, exceptions/tracebacks, and with-context managers.
 */
@Tag("logic")
class CoreLanguageAndExecutionModelTest {

    @Test
    @DisplayName("Numbers, BigInt, bitwise ops, slicing, comprehensions, walrus, and f-strings match CPython")
    void coreSyntaxAndOperatorsMatchCPython() {
        String code = """
            # Numeric literals, BigInt promotion, bitwise & shifts
            big = 1_000_000_000 * 1_000_000_000
            sci = 1.5e-3 + 2.5e2
            b = True + True
            bw = (0xFF_FF & 0x0F_0F) | (0b1010_0101 ^ 0o77)
            sh = (1 << 40) >> 10
            print(big, round(sci, 4), b, bw, sh, ~5, -7 // 3, -7 % 3)

            # Strings, escapes, f-strings, % formatting
            s = "café😀"
            print(len(s), s[1:5], s[::-1], f"{42:>5d} {s!r}", "%s=%04d" % ("n", 7))
            print("\\x41\\u00e9\\U0001f600\\a\\b\\f\\v\\0")

            # Chained comparisons, comprehensions, walrus :=
            print(1 < 2 <= 2 < 5, 1 in [1, 2] == True, not 1 == 2)
            vals = [y := x * 2 for x in range(5) if x % 2 == 0]
            print(vals, y)
            d = {k: k * k for k in range(4)}
            st = {x % 3 for x in range(7)}
            print(sorted(d.items()), sorted(st))

            # Unpacking and single-eval augmented assignment
            a, (b1, b2) = [10, (20, 30)]
            hits = []
            def idx():
                hits.append(1)
                return 0
            arr = [5]
            arr[idx()] += 7
            print(a, b1, b2, arr, len(hits))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Functions, *args/**kwargs, closures, nonlocal, global, and Execution Model §4.2.2 UnboundLocalError match CPython")
    void functionsScopeAndExecutionModelMatchCPython() {
        String code = """
            g = 100
            def outer(x, scale=2, *args, **kwargs):
                total = x
                def inner(delta):
                    nonlocal total
                    global g
                    total += delta * scale
                    g += 1
                    return total
                res = inner(5)
                return (res, total, args, sorted(kwargs.items()), g)
            print(outer(10, 3, 1, 2, k="v"))

            # Call-site *args/**kwargs unpacking
            def fn(a, b, c=10, d=20):
                return (a, b, c, d)
            print(fn(*[1, 2], **{"d": 99}))

            # Execution Model §4.2.2 UnboundLocalError (read before assign & post-del)
            x_glob = 55
            def ule_read():
                try:
                    v = x_glob
                    x_glob = 1
                    return v
                except UnboundLocalError:
                    return "ULE-read"
            def ule_del():
                y = 10
                del y
                try:
                    return y
                except UnboundLocalError:
                    return "ULE-del"
            print(ule_read(), ule_del(), x_glob)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("try/except/else/finally, PEP 3110, raise from, __traceback__, and with context managers match CPython")
    void exceptionsTracebackAndWithMatchCPython() {
        String code = """
            log = []
            class Ctx:
                def __init__(self, name, suppress=False):
                    self.name = name
                    self.suppress = suppress
                def __enter__(self):
                    log.append(("enter", self.name))
                    return self.name
                def __exit__(self, et, ev, tb):
                    log.append(("exit", self.name, et.__name__ if et else None))
                    return self.suppress

            def with_return():
                with Ctx("r"):
                    return 42

            def with_except():
                try:
                    with Ctx("e1", suppress=False):
                        raise ValueError("boom")
                except ValueError as err:
                    log.append(("caught", str(err)))
                with Ctx("e2", suppress=True):
                    raise KeyError("suppressed")

            print("ret:", with_return())
            with_except()

            # PEP 3110 deletion on normal & raise exits
            try:
                raise RuntimeError("r1")
            except RuntimeError as ex:
                seen = str(ex)
            try:
                _ = ex
            except NameError:
                log.append(("pep3110-normal", seen))

            try:
                try:
                    raise TypeError("t1")
                except TypeError as ex2:
                    raise ValueError("v1") from ex2
            except ValueError as outer_e:
                log.append(("cause", type(outer_e.__cause__).__name__, outer_e.__suppress_context__,
                            outer_e.__traceback__.tb_lineno > 0))
                try:
                    _ = ex2
                except NameError:
                    log.append("pep3110-raise")

            print(log)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("ExceptionTypeRegistry & PyErrors factory produce consistent per-interpreter exception classes")
    void exceptionRegistryAndErrorFactory() {
        var r1 = ExceptionTypeRegistry.standard();
        var r2 = ExceptionTypeRegistry.standard();
        PyClass val1 = r1.type("ValueError");
        PyClass val2 = r2.type("ValueError");
        assertNotNull(val1);
        assertTrue(val1 != val2, "Registries must isolate exception PyClass instances");
        assertSame(r1.baseException(), r1.type("BaseException"));

        var prev = ExceptionTypeRegistry.install(r1);
        try {
            InterpreterException ie = PyErrors.error("ZeroDivisionError", "div0");
            assertEquals("ZeroDivisionError", ie.typeName());
        } finally {
            ExceptionTypeRegistry.restore(prev);
        }
    }
}
