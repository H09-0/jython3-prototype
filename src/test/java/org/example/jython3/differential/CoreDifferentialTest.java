package org.example.jython3.differential;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.example.jython3.differential.DifferentialHarness.assertMatchesCPython;

/**
 * Executes differential tests comparing Jython 3 semantics directly with CPython.
 */
public class CoreDifferentialTest {

    @Test
    void testBaselineAudit() {
        String cpyVer = DifferentialHarness.getCPythonVersion();
        String cpyPath = DifferentialHarness.findCPython();
        System.out.println("[DifferentialBaseline] CPython executable: " + cpyPath);
        System.out.println("[DifferentialBaseline] CPython version:    " + cpyVer);
        System.out.println("[DifferentialBaseline] Jython version:     " + DifferentialHarness.JYTHON_VERSION);
        assertTrue(cpyPath != null, "A CPython binary must be available for differential conformance");
    }

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

    @Test
    void testNestedControlFlowDifferential() {
        assertMatchesCPython("""
            for i in range(3):
                for j in range(3):
                    if j == 1:
                        break
                    print(i, j)
            """);
        assertMatchesCPython("""
            for a in range(2):
                for b in range(3):
                    if b == 1:
                        continue
                    if b == 2:
                        break
                    print(a, b)
            """);
    }

    @Test
    void testBuiltinTypesDifferential() {
        assertMatchesCPython("print(isinstance(42, int))");
        assertMatchesCPython("print(isinstance(10**30, int))");
        assertMatchesCPython("print(isinstance(True, bool))");
        assertMatchesCPython("print(isinstance(True, int))");
        assertMatchesCPython("print(isinstance(42, bool))");
        assertMatchesCPython("print(isinstance(3.14, float))");
        assertMatchesCPython("print(isinstance('hi', str))");
        assertMatchesCPython("print(isinstance([1, 2], list))");
        assertMatchesCPython("print(isinstance({'a': 1}, dict))");
        assertMatchesCPython("print(isinstance(42, (int, str)))");
        assertMatchesCPython("print(isinstance(42, (str, list)))");
        assertMatchesCPython("print(issubclass(bool, int))");
        assertMatchesCPython("print(issubclass(int, bool))");
        assertMatchesCPython("print(issubclass(int, int))");
        assertMatchesCPython("print(issubclass(bool, (int, str)))");
    }

    @Test
    void testBooleanArithmeticDifferential() {
        assertMatchesCPython("print(True + True)");
        assertMatchesCPython("print(isinstance(True + True, int))");
        assertMatchesCPython("print(isinstance(True + True, bool))");
        assertMatchesCPython("print(True * 5)");
        assertMatchesCPython("print(True + 0.5)");
        assertMatchesCPython("print(True / 2)");
        assertMatchesCPython("print(True // 2)");
        assertMatchesCPython("print(True % 2)");
        assertMatchesCPython("print(-True)");
        assertMatchesCPython("print('ab' * True)");
        assertMatchesCPython("print('ab' * False)");
        assertMatchesCPython("print([1, 2] * True)");
    }

    @Test
    void testStringFormattingDifferential() {
        assertMatchesCPython("print('%d %s' % (10, 'hi'))");
        assertMatchesCPython("print('%s is %d years old (%.1f)' % ('Alice', 30, 95.5))");
        assertMatchesCPython("print('%d%% done' % 100)");
        assertMatchesCPython("print('%d' % (10 ** 25))");
        assertMatchesCPython("print('%d' % True)");
        assertMatchesCPython("print('%r' % 'hello')");
        assertMatchesCPython("try:\n    print('%d %d' % 1)\nexcept TypeError:\n    print('too_few_caught')");
        assertMatchesCPython("try:\n    print('%d' % (1, 2))\nexcept TypeError:\n    print('too_many_caught')");
    }

    @Test
    void testBigIntAndFloatOperationsDifferential() {
        assertMatchesCPython("print((10 ** 400) / (10 ** 300))");
        assertMatchesCPython("print((10 ** 300) / (10 ** 400))");
        assertMatchesCPython("print(3 / (10 ** 400))");
        assertMatchesCPython("print(0 / (10 ** 400))");
        assertMatchesCPython("try:\n    print((10 ** 400) / 3)\nexcept OverflowError:\n    print('div_overflow_caught')");
        assertMatchesCPython("try:\n    print((10 ** 1000) / 3)\nexcept OverflowError:\n    print('div_1000_overflow_caught')");
        assertMatchesCPython("try:\n    print(float(10 ** 400))\nexcept OverflowError:\n    print('float_cast_overflow_caught')");
        assertMatchesCPython("try:\n    print((10 ** 400) * 1.0)\nexcept OverflowError:\n    print('mul_float_overflow_caught')");
        assertMatchesCPython("try:\n    print((10 ** 400) + 1.0)\nexcept OverflowError:\n    print('add_float_overflow_caught')");
        assertMatchesCPython("try:\n    print(2.0 ** 1024)\nexcept OverflowError:\n    print('pow_float_overflow_caught')");
        assertMatchesCPython("try:\n    print((10 ** 400) ** -1)\nexcept OverflowError:\n    print('neg_pow_overflow_caught')");
        assertMatchesCPython("try:\n    print(1 / 0.0)\nexcept ZeroDivisionError:\n    print('float_div0_caught')");
        assertMatchesCPython("try:\n    print(1.0 / 0)\nexcept ZeroDivisionError:\n    print('float_div0_caught2')");
    }

    @Test
    void testUnicodeDifferential() {
        assertMatchesCPython("s = 'Python3😀🚀'; print(len(s), s[0], s[7], s[8], s[-1], s[-2])");
        assertMatchesCPython("s = 'Python3😀🚀'; print(s[0:7], s[7:], s[::-1])");
        assertMatchesCPython("print(list('😀🚀'))");
        assertMatchesCPython("out = []\nfor c in '😀🚀':\n    out.append(c)\nprint(out)");
        assertMatchesCPython("print(sorted('cba🚀😀'))");
        assertMatchesCPython("print(list(reversed('abc🚀')))");
    }

    @Test
    void testComparisonsDifferential() {
        assertMatchesCPython("comp_lt", "print(1 < 2)");
        assertMatchesCPython("comp_gt", "print(2 > 1)");
        assertMatchesCPython("comp_le", "print(2 <= 2)");
        assertMatchesCPython("comp_ge", "print(2 >= 2)");
        assertMatchesCPython("comp_eq", "print(3 == 3)");
        assertMatchesCPython("comp_ne", "print(3 != 4)");
    }

    @Test
    void testDictOperationsDifferential() {
        assertMatchesCPython("dict_get_default", """
            d = {'a': 1}
            print(d.get('a'), d.get('b'), d.get('b', 99))
            """);
        assertMatchesCPython("dict_keys_values", """
            d = {'x': 10, 'y': 20}
            print(sorted(d.keys()), sorted(d.values()))
            """);
    }

    @Test
    void testStringMethodsDifferential() {
        assertMatchesCPython("str_case_strip", "print('hello world'.upper(), 'HELLO'.lower(), '  abc  '.strip())");
        assertMatchesCPython("str_split_join", "print(','.join(['a', 'b', 'c']), 'a:b:c'.split(':'))");
        assertMatchesCPython("str_prefix_suffix", "print('hello'.startswith('hel'), 'world'.endswith('rld'), 'banana'.count('an'))");
    }

    @Test
    void testTupleDifferential() {
        assertMatchesCPython("tup_literal", "print((), (1,), (1, 2))");
        assertMatchesCPython("tup_ne_list", "print((1, 2) == [1, 2])");
        assertMatchesCPython("tup_dict_key", "d = {(1, 2): 'val', ('a', 'b'): 99}; print(d[(1, 2)], d[('a', 'b')])");
        assertMatchesCPython("tup_unhashable", "try:\n    d = {(1, [2]): 'err'}\nexcept TypeError:\n    print('unhashable_caught')");
        assertMatchesCPython("tup_immutable", "try:\n    t = (1, 2); t[0] = 99\nexcept TypeError:\n    print('mutate_caught')");
        assertMatchesCPython("tup_concat_repeat", "t = (1, 2); print(t + (3,), t * 2)");
        assertMatchesCPython("tup_types", "t = (1, 2); print(isinstance(t, tuple), isinstance(t, list), isinstance([1, 2], tuple))");
    }

    @Test
    void testDefaultAndKeywordArgsDifferential() {
        assertMatchesCPython("default_simple",
                "def f(x, y=10): return x + y\nprint(f(1), f(1, 2), f(1, y=99))");
        assertMatchesCPython("default_multi",
                "def g(a=1, b=2, c=3): return a * 100 + b * 10 + c\nprint(g(), g(5), g(5, 6), g(c=9))");
        assertMatchesCPython("varargs_total",
                "def total(*ns):\n    s=0\n    for n in ns: s+=n\n    return s\nprint(total(), total(1,2,3), total(10,20,30))");
        assertMatchesCPython("kwargs_dict",
                "def d(**kw): return sorted(kw.keys())\nprint(d(z=3, a=1, m=2))");
        assertMatchesCPython("mixed_all",
                "def f(a, b=10, *args, **kw): return [a, b, len(args), len(kw.keys())]\n" +
                "print(f(1), f(1, 2), f(1, 2, 3, 4), f(1, x=9, y=8))");
        assertMatchesCPython("default_at_def_time",
                "x=10\ndef f(a=x): return a\nx=99\nprint(f())");
        assertMatchesCPython("keyword_only_call",
                "def add(a, b): return a + b\nprint(add(a=3, b=4))");
        assertMatchesCPython("too_few_args_error",
                "def f(a, b): pass\ntry:\n    f(1)\nexcept TypeError:\n    print('caught')");
        assertMatchesCPython("unexpected_kwarg_error",
                "def f(a): pass\ntry:\n    f(a=1, z=99)\nexcept TypeError:\n    print('caught')");
    }

    @Test
    void testSprintASyntaxDifferential() {
        assertMatchesCPython("listcomp", "print([x * 2 for x in range(5)], [x for x in range(8) if x % 2 == 1])");
        assertMatchesCPython("dictcomp", "print({k: k * 10 for k in range(3)})");
        assertMatchesCPython("setcomp", "print({x % 2 for x in range(4)})");
        assertMatchesCPython("lambda_basic", "f = lambda x: x * 3; print(f(7))");
        assertMatchesCPython("lambda_defaults", "g = lambda a, b=10: a + b; print(g(2), g(2, 5))");
        assertMatchesCPython("unpack_pairs", "a, b = 1, 2; print(a, b)");
        assertMatchesCPython("unpack_from_list", "x, y = [10, 20]; print(x, y)");
        assertMatchesCPython("fstring", "a = 'world'; n = 42; print(f'hello {a} {n+1}')");
        assertMatchesCPython("ternary", "print(10 if True else 20, 10 if False else 20)");
        assertMatchesCPython("unary_plus", "print(+5, +-3, ++7)");
        assertMatchesCPython("assert_ok", "assert 1 == 1; print('assert_passed')");
        assertMatchesCPython("assert_caught", "try:\n    assert 1 == 2, 'fail'\nexcept AssertionError as e:\n    print(str(e))");
    }
}
