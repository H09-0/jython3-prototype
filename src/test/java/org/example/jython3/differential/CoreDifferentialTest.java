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
        // Division and modulo by zero differential checks: catch ZeroDivisionError
        assertMatchesCPython("try:\n    x = 1 // 0\nexcept ZeroDivisionError:\n    print('div0_caught')");
        assertMatchesCPython("try:\n    x = 1 % 0\nexcept ZeroDivisionError:\n    print('mod0_caught')");
        assertMatchesCPython("try:\n    x = 100000000000000000000 // 0\nexcept ZeroDivisionError:\n    print('big_div0_caught')");
        assertMatchesCPython("try:\n    x = 100000000000000000000 % 0\nexcept ZeroDivisionError:\n    print('big_mod0_caught')");
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

    @Test
    void testSlicingEdgeCasesDifferential() {
        // Empty results from out-of-range bounds
        assertMatchesCPython("print([1, 2, 3][100:])");
        assertMatchesCPython("print([1, 2, 3][-100:])");
        // BigInt bounds — should clamp to sequence edges
        assertMatchesCPython("big = 10 ** 100; print([1, 2, 3][big:])");
        assertMatchesCPython("big = 10 ** 100; print([1, 2, 3][-big:])");
        assertMatchesCPython("big = 10 ** 100; print([1, 2, 3][:big])");
        assertMatchesCPython("big = 10 ** 100; print([1, 2, 3][:-big])");
        // Float bounds — should raise TypeError
        assertMatchesCPython("try:\n    x = [1, 2, 3][1.0:2]\nexcept TypeError:\n    print('float_start_rejected')");
        assertMatchesCPython("try:\n    x = [1, 2, 3][1:2.0]\nexcept TypeError:\n    print('float_stop_rejected')");
        assertMatchesCPython("try:\n    x = [1, 2, 3][::1.0]\nexcept TypeError:\n    print('float_step_rejected')");
        // Step zero
        assertMatchesCPython("try:\n    x = [1, 2, 3][::0]\nexcept ValueError:\n    print('step_zero_rejected')");
        // String slicing with BigInt
        assertMatchesCPython("big = 10 ** 100; print('abcdef'[big:])");
        assertMatchesCPython("big = 10 ** 100; print('abcdef'[-big:])");
    }
}
