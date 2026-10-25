package org.example.jython3.differential;

import org.junit.jupiter.api.Test;
import static org.example.jython3.differential.DifferentialHarness.assertMatchesCPython;

/**
 * Executes differential tests comparing Jython 3 semantics directly with CPython.
 */
public class CoreDifferentialTest {

    @Test
    void testTrueDivisionDifferential() {
        assertMatchesCPython("print(5 / 2)");
        assertMatchesCPython("print(4 / 2)");
        assertMatchesCPython("print(-5 / 2)");
        assertMatchesCPython("print(1.0 / 2)");
        assertMatchesCPython("try:\n    print(1 / 0)\nexcept ZeroDivisionError as e:\n    print('caught zero div')");
    }

    @Test
    void testFloorDivisionAndModuloDifferential() {
        assertMatchesCPython("print(7 // 2)");
        assertMatchesCPython("print(-7 // 2)");
        assertMatchesCPython("print(7 % 2)");
        assertMatchesCPython("print(-7 % 2)");
        // BigInt differential tests across all sign combinations
        assertMatchesCPython("a = 100000000000000000000; b = 3; print(a // b, a % b)");
        assertMatchesCPython("a = 100000000000000000000; b = 3; print(a // -b, a % -b)");
        assertMatchesCPython("a = 100000000000000000000; b = 3; print(-a // b, -a % b)");
        assertMatchesCPython("a = 100000000000000000000; b = 3; print(-a // -b, -a % -b)");
    }

    @Test
    void testExponentiationDifferential() {
        assertMatchesCPython("print(2 ** 0)");
        assertMatchesCPython("print(2 ** 8)");
        assertMatchesCPython("print(2 ** -2)");
        assertMatchesCPython("print(2 ** 31)");
    }

    @Test
    void testNumericComparisonsDifferential() {
        assertMatchesCPython("print(1 == 1.0)");
        assertMatchesCPython("print(0 == -0.0)");
        assertMatchesCPython("print(9007199254740993 == 9007199254740992.0)");
        assertMatchesCPython("print(9007199254740993 > 9007199254740992.0)");
        assertMatchesCPython("print(float('nan') == float('nan'))");
        assertMatchesCPython("print(float('nan') < 1.0)");
    }

    @Test
    void testDictionaryKeySemanticsDifferential() {
        assertMatchesCPython("""
            d = {}
            d[1] = "int"
            d[1.0] = "float"
            d[True] = "bool"
            print(len(d))
            print(d[1])
            print(d[1.0])
            print(d[True])
            """);
        assertMatchesCPython("""
            try:
                d = {}
                d[[1, 2]] = 3
            except TypeError:
                print('unhashable list')
            """);
    }

    @Test
    void testSlicingDifferential() {
        assertMatchesCPython("print([10, 20, 30, 40, 50][1:4])");
        assertMatchesCPython("print([10, 20, 30, 40, 50][:3])");
        assertMatchesCPython("print([10, 20, 30, 40, 50][2:])");
        assertMatchesCPython("print([10, 20, 30, 40, 50][::2])");
        assertMatchesCPython("print([10, 20, 30, 40, 50][::-1])");
        assertMatchesCPython("print('Python3😀🚀'[0:6])");
        assertMatchesCPython("print('Python3😀🚀'[6:])");
        assertMatchesCPython("print('Python3😀🚀'[::-1])");
    }
}
