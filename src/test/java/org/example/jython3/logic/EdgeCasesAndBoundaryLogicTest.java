package org.example.jython3.logic;

import org.example.jython3.differential.DifferentialHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Granular edge-case & boundary conformance tests for numeric IEEE-754/BigInt,
 * Unicode codepoints, control-flow else clauses, multi-level closures, and classes inside functions.
 */
@Tag("logic")
class EdgeCasesAndBoundaryLogicTest {

    @Test
    @DisplayName("IEEE-754 float edge cases, BigInt exact division/pow/divmod, and NaN/Inf match CPython")
    void numericAndIeee754BoundariesMatchCPython() {
        String code = """
            nan = float("nan")
            inf = float("inf")
            ninf = float("-inf")
            print(nan == nan, nan != nan, -0.0 == 0.0, inf > 10**50, ninf < -10**50)
            big = 10**30 + 7
            print(big // 3, big % 3, divmod(big, 10**10), pow(2, 100, 97))
            print(round(0.5), round(1.5), round(2.5), round(3.5), round(-0.5), round(-1.5))
            print(round(1234, -2), round(1250, -2), round(1350, -2))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Control flow (for/else, while/else, try/else/finally override) and nested scopes match CPython")
    void controlFlowElseAndNestedScopesMatchCPython() {
        String code = """
            # for/else and while/else (with and without break)
            log = []
            for i in range(3):
                if i == 5: break
            else:
                log.append("for-else-ok")
            for i in range(3):
                if i == 1: break
            else:
                log.append("should-not-run")

            n = 2
            while n > 0:
                n -= 1
            else:
                log.append("while-else-ok")

            # try/except/else/finally return override
            def finally_override():
                try:
                    return 1
                finally:
                    return 99

            def try_else_path():
                try:
                    x = 10
                except ValueError:
                    return -1
                else:
                    return x + 5
                finally:
                    log.append("finally-ran")

            # 3-level closure & class inside function
            def level1(a):
                def level2(b):
                    class LocalBox:
                        def compute(self, c):
                            return a + b + c
                    return LocalBox()
                return level2(20)

            box = level1(10)
            print(finally_override(), try_else_path(), box.compute(3), log)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Unicode emoji codepoints, bytes/bytearray hex/fromhex, and tuple/list slicing match CPython")
    void unicodeBytesAndSlicingBoundariesMatchCPython() {
        String code = """
            s = "A😀B🎉C"
            print(len(s), s[1], s[-2], s[1:4], s[::-1], ord(s[1]), chr(127881))
            b = bytes.fromhex("48656c6c6f")
            ba = bytearray.fromhex("41 42 43")
            ba[1:2] = b"XY"
            print(b.decode("utf-8"), b.hex(), ba.decode("utf-8"), ba.hex())
            xs = [0, 1, 2, 3, 4, 5]
            xs[1:4] = [10, 20]
            del xs[::2]
            print(xs)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }
}
