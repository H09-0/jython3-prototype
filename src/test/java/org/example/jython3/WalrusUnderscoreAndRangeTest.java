package org.example.jython3;

import org.example.jython3.differential.DifferentialHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Track 2A: numeric underscore literals, scientific floats,
 * walrus assignment expressions (:=), and lazy O(1) PyRange.
 */
class WalrusUnderscoreAndRangeTest {

    @Test
    @DisplayName("Numeric underscore literals and scientific floats match CPython")
    void numericUnderscoresAndScientificFloats() {
        String code = """
            a = 1_000_000
            b = 0xFF_EC_00
            c = 0b1010_0101
            d = 0o7_5_5
            f1 = 1_234.5_6
            f2 = 1.5e-3
            f3 = 2_0e+2
            print(a, b, c, d, f1, f2, f3)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Walrus operator (:=) works in if, while, calls, and comprehensions matching CPython")
    void walrusOperatorMatchesCPython() {
        String code = """
            data = [3, 8, 2, 11, 5]
            if (n := len(data)) > 3:
                print("len", n)

            out = [y for x in data if (y := x * 2) > 10]
            print(out, y)

            def next_item(xs):
                return xs.pop(0) if xs else None

            buf = [10, 20, 30]
            seen = []
            while (val := next_item(buf)) is not None:
                seen.append(val)
            print(seen)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Lazy PyRange supports O(1) slicing, membership, equality, hashing, reversed, and huge bounds")
    void lazyPyRangeMatchesCPython() {
        String code = """
            r1 = range(10)
            r2 = range(2, 20, 3)
            print(len(r1), len(r2), r2.start, r2.stop, r2.step)
            print(r2[0], r2[-1], r2[1:4], list(r2[1:4]))
            print(5 in r2, 8 in r2, r2.count(8), r2.count(7), r2.index(14))
            print(range(0, 4, 2) == range(0, 3, 2), hash(range(0, 4, 2)) == hash(range(0, 3, 2)))
            print(list(reversed(range(1, 10, 2))))
            print(isinstance(r1, range), type(r1) is range, repr(r2))

            huge = range(0, 10**25, 5)
            print(10**24 in huge, (10**24 + 1) in huge, huge[10**20])
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }
}
