package org.example.jython3;

import org.example.jython3.differential.DifferentialHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for Phase 1 core language completeness: PEP 3110 exception-target deletion
 * on raise exits, full string escapes, slice() constructor & indices(), sorted() DSU,
 * reversed(), iter(callable, sentinel), pow(a, b, mod), divmod(), Unicode ord/chr,
 * negative/BigInt hex/oct/bin, dict(pairs/kwargs), and round().
 */
class CoreLanguageCompletenessTest {

    @Test
    @DisplayName("PEP 3110 deletes 'as e' binding on raise exit even without finally")
    void pep3110DeletionOnRaiseExitWithoutFinally() {
        String code = """
            def f():
                try:
                    raise ValueError("inner")
                except ValueError as e:
                    try:
                        raise RuntimeError("boom")
                    except RuntimeError:
                        pass
                # Re-test raise escaping the except handler directly
            def g():
                e = "outer"
                try:
                    try:
                        1 // 0
                    except ZeroDivisionError as e:
                        raise KeyError("escape")
                except KeyError:
                    pass
                try:
                    return ("leaked", e)
                except UnboundLocalError:
                    return ("deleted", True)

            print(g())
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("String escapes (\\xHH, \\uHHHH, \\UHHHHHHHH, \\0, octal, \\a\\b\\f\\v, continuation) match CPython")
    void stringEscapesMatchCPython() {
        String code = """
            s1 = "\\x41\\x00\\x7a"
            s2 = "\\u03b1\\u03b2\\U0001f680"
            s3 = "\\0\\7\\77\\101\\a\\b\\f\\v"
            s4 = "hello\\
            world"
            print(len(s1), ord(s1[1]), s1[0], s1[2])
            print(s2, len(s2), ord(s2[2]), chr(0x1F680))
            print(len(s3), ord(s3[0]), ord(s3[1]), ord(s3[2]), s3[3])
            print(repr(s1))
            print(s4.strip())
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Core builtins (slice, pow mod, divmod, hex/oct/bin, round, sorted DSU, reversed, iter sentinel, dict pairs) match CPython")
    void builtinsMatchCPython() {
        String code = """
            sl = slice(1, 10, 2)
            print(sl.start, sl.stop, sl.step, sl.indices(6))
            print([10, 20, 30, 40, 50][slice(1, None, 2)])

            print(pow(2, 10), pow(2, 10, 1000), pow(3, -1, 7), pow(10, 25, 97))
            print(divmod(17, 5), divmod(-17, 5), divmod(7.5, 2.0))
            print(hex(-255), oct(-64), bin(-5), hex(10**20))
            print(abs(-2147483648), abs(-10**30), abs(True))
            print(round(2.5), round(3.5), round(1234, -2), round(3.14159, 2))

            calls = 0
            def key_fn(x):
                nonlocal calls
                calls += 1
                return -x
            print(sorted([3, 1, 4, 2], key=key_fn), calls)

            print(list(reversed([1, 2, 3])), list(reversed("abc")))

            n = 0
            def step():
                nonlocal n
                n += 1
                return n
            print(list(iter(step, 4)))

            d1 = dict([("a", 1), ("b", 2)], c=3)
            print(d1["a"], d1["b"], d1["c"])
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("slice(stop) negative indices & error paths match CPython")
    void sliceErrorsMatchCPython() {
        try (var py = new Jython3Interpreter()) {
            py.eval("""
                s = slice(5)
                assert s.start is None and s.stop == 5 and s.step is None
                assert s.indices(3) == (0, 3, 1)
                """);
            assertEquals("ValueError",
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.example.jython3.runtime.InterpreterException.class,
                            () -> py.eval("pow(2, 3, 0)")).typeName());
        }
    }
}
