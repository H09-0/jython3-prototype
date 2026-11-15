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

    @Test
    void testBitwiseOperationsDifferential() {
        assertMatchesCPython("bitwise_basic",
                "print(5 & 3, 5 | 2, 5 ^ 1, ~5, ~0, ~(-6), 1 << 4, 16 >> 2)");
        assertMatchesCPython("bitwise_precedence",
                "print(1 | 2 ^ 3 & 4 << 1 + 1, ~1 + 5, 10 >> 1 & 3)");
        assertMatchesCPython("bitwise_bigint",
                "big = (1 << 100) | (1 << 50) | 7\n" +
                "print(big & 7, (big >> 50) & 1, (big ^ (1 << 100)) >> 50, ~big)");
        assertMatchesCPython("bitwise_aug_assign",
                "x = 12\nx &= 10\nx |= 5\nx ^= 3\nx <<= 2\nx >>= 1\nprint(x)");
        assertMatchesCPython("bitwise_bool",
                "print(True & False, True | False, True ^ True, True & 1, ~True, True << 3)");
        assertMatchesCPython("bitwise_float_error",
                "try:\n    x = 5 & 1.5\nexcept TypeError:\n    print('float_rejected')");
        assertMatchesCPython("bitwise_neg_shift_error",
                "try:\n    x = 1 << -2\nexcept ValueError:\n    print('neg_shift_rejected')");
    }

    @Test
    void testTryFinallyControlFlowDifferential() {
        assertMatchesCPython("finally_override_return",
                "def f():\n    try:\n        return 'try'\n    finally:\n        return 'finally'\nprint(f())");
        assertMatchesCPython("finally_override_exception",
                "def f():\n    try:\n        raise RuntimeError('outer')\n    finally:\n        raise ValueError('inner')\ntry:\n    f()\nexcept ValueError as e:\n    print('caught_inner', str(e))");
        assertMatchesCPython("finally_continue_overrides_break",
                "events = []\nfor x in [1, 2]:\n    try:\n        events.append(('try', x))\n        break\n    finally:\n        events.append(('fin', x))\n        continue\n    events.append(('after', x))\nprint(events)");
        assertMatchesCPython("finally_continue_overrides_raise",
                "events = []\nfor x in [1, 2]:\n    try:\n        events.append(('try', x))\n        raise ValueError('boom')\n    finally:\n        events.append(('fin', x))\n        continue\nprint(events)");
        assertMatchesCPython("finally_break_overrides_return",
                "def f():\n    events = []\n    for x in [1, 2]:\n        try:\n            return 10\n        finally:\n            events.append(x)\n            break\n    return events\nprint(f())");
        assertMatchesCPython("finally_return_preserves_evaluated_val",
                "def f():\n    x = 1\n    try:\n        return x\n    finally:\n        x = 2\nprint(f())");
        assertMatchesCPython("finally_except_return_and_break",
                "def f():\n    log = []\n    try:\n        raise KeyError('k')\n    except KeyError:\n        log.append('except')\n        return ('ret', log)\n    finally:\n        log.append('finally')\nprint(f())");
        assertMatchesCPython("finally_nested_depth_3",
                "def f():\n    log = []\n    try:\n        try:\n            try:\n                log.append('body')\n                return ('ok', log)\n            finally:\n                log.append('f3')\n        finally:\n            log.append('f2')\n    finally:\n        log.append('f1')\nprint(f())");
        assertMatchesCPython("finally_nested_depth_5_raise",
                "log = []\ntry:\n    try:\n        try:\n            try:\n                try:\n                    raise ValueError('deep')\n                finally:\n                    log.append(5)\n            finally:\n                log.append(4)\n        finally:\n            log.append(3)\n    finally:\n        log.append(2)\nexcept ValueError:\n    log.append('caught')\nfinally:\n    log.append(1)\nprint(log)");
        assertMatchesCPython("finally_with_bitwise",
                "log = []\ndef f(x):\n    try:\n        x &= 3\n        return (1 << 100) | x\n    finally:\n        log.append(1 | 2)\nres = f(7)\nprint(res, log)");
    }

    private static final String CM_CLASS =
            "class CM:\n"
            + "    def __init__(self, log, tag):\n"
            + "        self.log = log\n"
            + "        self.tag = tag\n"
            + "    def __enter__(self):\n"
            + "        self.log.append('enter-' + self.tag)\n"
            + "        return self\n"
            + "    def __exit__(self, t, v, tb):\n"
            + "        self.log.append('exit-' + self.tag)\n"
            + "        return False\n";

    @Test
    void testWithInsideFunctionsDifferential() {
        assertMatchesCPython("with_in_function_runs_to_end", CM_CLASS
                + "def f(log):\n    log.append('start')\n    with CM(log, 'a'):\n        log.append('body')\n    log.append('end')\n    return 1\n"
                + "l = []\nprint(f(l), l)");
        assertMatchesCPython("with_return_then_finally", CM_CLASS
                + "def f(log):\n    try:\n        with CM(log, 'a'):\n            return 7\n    finally:\n        log.append('finally')\n"
                + "l = []\nprint(f(l), l)");
        assertMatchesCPython("with_break_continue_exit_each_time", CM_CLASS
                + "l = []\nfor i in range(3):\n    with CM(l, str(i)):\n        if i == 0:\n            continue\n        if i == 1:\n            break\nprint(l)");
        assertMatchesCPython("with_nested_return_in_loops", CM_CLASS
                + "def f(log):\n    for a in [1, 2]:\n        with CM(log, 'o'):\n            for b in [10, 20]:\n                with CM(log, 'i'):\n                    if b == 20:\n                        return a + b\n"
                + "l = []\nprint(f(l), l)");
        assertMatchesCPython("with_exception_propagates_through_function", CM_CLASS
                + "def f(log):\n    with CM(log, 'a'):\n        raise KeyError('boom')\n"
                + "l = []\ntry:\n    f(l)\nexcept KeyError:\n    l.append('caught')\nprint(l)");
        assertMatchesCPython("else_raise_runs_finally",
                "l = []\ntry:\n    try:\n        l.append('try')\n    except KeyError:\n        l.append('except')\n    else:\n        l.append('else')\n        raise ValueError('e')\n    finally:\n        l.append('finally')\nexcept ValueError:\n    l.append('outer')\nprint(l)");
    }

    @Test
    void testSetEqualityDifferential() {
        assertMatchesCPython("set_equality",
                "print({0, 1} == {1, 0}, {0} != {1}, {1, 2} == {1}, {1} == {1.0}, ({'a'},) == ({'a'},))");
    }

    @Test
    void testNumericKeyHashEqualityDifferential() {
        assertMatchesCPython("dict_equality_numeric_keys",
                "print({1: 0} == {1.0: 0}, {True: 1} == {1: 1}, {0: 'a'} == {False: 'a'}, {'a': 1} == {'a': 1.0})\n"
                + "print({1: 0} != {1.0: 0}, {(1, 2): 't'} == {(1.0, 2): 't'}, {10 ** 20: 1} == {float(10 ** 20): 1})\n"
                + "print({1: [1, 2]} == {1: [1, 2]}, {1: {2: 3}} == {1: {2: 3}}, {1: 0} == {1: 0, 2: 0})");
        assertMatchesCPython("dict_numeric_key_collapse",
                "d = {1: 'int', 2: 'two'}\nd[1.0] = 'float'\nd[True] = 'bool'\nprint(len(d), d[1], list(d.keys()), d)\n"
                + "lit = {1: 'a', 1.0: 'b', True: 'c'}\nprint(lit, len(lit))\n"
                + "print({1: 'a'}[1.0], {1.0: 'a'}[True], hash(1) == hash(1.0) and hash(1) == hash(True))\n"
                + "print({((1, 2), (True,)): 'v'}[((1.0, 2.0), (1,))], 10**400 == True, 10**400 == 1.0)\n"
                + "print({1: 0}.get(1.0), 1.0 in {1: 0}, True in {1: 0}, 2 in {1: 0})");
        assertMatchesCPython("set_numeric_collapse_and_membership",
                "print(len({1, 1.0, True}), len({0, 0.0, False}), 1.0 in {1}, True in {1}, len({(1, 2), (1.0, 2)}))\n"
                + "print({1, 2} == {2.0, 1.0}, len({10 ** 20, float(10 ** 20)}), 'a' in {'a'}, None in {None})\n"
                + "s = set()\nprint(s, len(s), bool(s), bool({0}))\n"
                + "print({'x'}, {1.5}, sorted({3, 1, 2}))\n"
                + "c = {v % 3 for v in range(10)}\nprint(sorted(c), len(c))\n"
                + "print(len(set([1, 1.0, 2, True])), sorted(set('abca')), isinstance({1}, set), isinstance([1], set))\n"
                + "s2 = {1, 2}\ns2.add(3)\ns2.discard(2.0)\ns2.remove(3)\nprint(sorted(s2))\n"
                + "print(sorted({1, 2}.union([2.0, 3])), {1, 2, 3}.intersection([2.0, 3]) == {2, 3}, sorted({1, 2, 3}.difference([2.0])))");
        assertMatchesCPython("unhashable_keys_raise_type_error",
                "for label, make, typ in [('list-key', lambda: {[1]: 1}, 'list'), ('set-elem', lambda: {[1]}, 'list'),\n"
                + "                         ('set-key', lambda: {{1}: 1}, 'set'), ('dict-in-set', lambda: {{}}, 'dict')]:\n"
                + "    try:\n        make()\n        print(label, 'no-error')\n"
                + "    except TypeError as e:\n        print(label, 'TypeError', ('unhashable type: ' + repr(typ)) in str(e))\n"
                + "try:\n    r = [1] in {1: 0}\nexcept TypeError as e:\n    print('list-in-dict', \"unhashable type: 'list'\" in str(e))\n"
                + "try:\n    r = [1] in {1}\nexcept TypeError as e:\n    print('list-in-set', \"unhashable type: 'list'\" in str(e))");
    }

    @Test
    void testExceptionMetadataAndDunderNameDifferential() {
        assertMatchesCPython("exception_context_and_raise_from",
                "saved = None\n"
                + "try:\n    try:\n        raise KeyError('k')\n    except KeyError as orig:\n        saved = orig\n        raise ValueError('v')\nexcept ValueError as e:\n"
                + "    print(e.__context__ is saved, type(e.__context__) == KeyError, e.__cause__ is None, e.__suppress_context__, e.args)\n"
                + "saved2 = None\n"
                + "try:\n    try:\n        raise KeyError('k2')\n    except KeyError as orig2:\n        saved2 = orig2\n        raise ValueError('v2') from orig2\nexcept ValueError as e2:\n"
                + "    print(e2.__cause__ is saved2, e2.__context__ is saved2, e2.__suppress_context__)\n"
                + "try:\n    try:\n        raise KeyError('k3')\n    except KeyError:\n        raise ValueError('v3') from None\nexcept ValueError as e3:\n"
                + "    print(e3.__cause__ is None, e3.__suppress_context__, e3.__context__.__class__.__name__)");
        assertMatchesCPython("dunder_name_and_class",
                "class C:\n    def m(self):\n        pass\n"
                + "def f():\n    pass\n"
                + "x = C()\n"
                + "print(C.__name__, ValueError.__name__, x.__class__ is C, type(x).__name__, f.__name__, len.__name__, x.m.__name__)");
        assertMatchesCPython("unified_type_identity",
                "class C:\n    def m(self):\n        pass\n"
                + "def f():\n    pass\n"
                + "print(type(1), type('x'), type([]), type({}), type(set()), type(ValueError()))\n"
                + "print(type(1) is int, type('x') is str, type([]) is list, type({}) is dict, type(set()) is set)\n"
                + "print(type(ValueError()) is ValueError, type(C()).__name__, type(f).__name__, type(len).__name__, type(None).__name__)\n"
                + "print(isinstance(True, int), issubclass(bool, int), issubclass(C, object), issubclass(int, object), issubclass(C, int))\n"
                + "print((1).__class__ is int, 'x'.__class__ is str, [].__class__ is list, {}.__class__ is dict, set().__class__ is set)");
        assertMatchesCPython("traceback_frames_and_with_tb",
                "def tb_names(exc):\n    out = []\n    tb = exc.__traceback__\n    while tb is not None:\n        out.append((tb.tb_frame.f_code.co_name, tb.tb_lineno))\n        tb = tb.tb_next\n    return out\n"
                + "def boom():\n    raise ValueError('x')\n"
                + "def middle():\n    try:\n        boom()\n    finally:\n        pass\n"
                + "def top():\n    middle()\n"
                + "try:\n    top()\nexcept ValueError as e:\n    print('fin:', tb_names(e))\n"
                + "class CM:\n    def __enter__(self):\n        return self\n"
                + "    def __exit__(self, t, v, tb):\n        print('exit_tb:', tb is not None, tb.tb_frame.f_code.co_name, tb.tb_next.tb_frame.f_code.co_name)\n        return True\n"
                + "def with_fn():\n    with CM():\n        boom()\n"
                + "with_fn()\n"
                + "def helper():\n    return 1 / 0\n"
                + "try:\n    try:\n        raise KeyError('k')\n    except KeyError:\n        helper()\nexcept ZeroDivisionError as z:\n    print('helper_ctx:', z.__context__.__class__.__name__)");
        assertMatchesCPython("core_builtins",
                "def g():\n    yield 0\n    yield 1\n    yield 2\n"
                + "print(all([1, True, 'a']), all([1, 0]), any([0, '', 2]), any([0, False]))\n"
                + "print(callable(g), callable(len), callable(int), callable(123))\n"
                + "print(repr('a'), repr(\"it's\"), repr('a\\nb'), repr(ValueError('msg')))\n"
                + "print(list(enumerate('ab', start=3)), list(zip([1, 2], ('x', 'y'))))\n"
                + "print(list(map(lambda x: x + 1, g())), list(filter(None, [0, 1, '', 'ok'])))\n"
                + "print(sum(g(), start=10))\n"
                + "class K:\n    def __init__(self):\n        self.a = 1\n"
                + "k = K()\nsetattr(k, 'b', 2)\nprint(vars(k) == {'a': 1, 'b': 2}, hasattr(k, 'b'))\n"
                + "delattr(k, 'b')\nprint(hasattr(k, 'b'), getattr(k, 'b', 99), 'a' in dir(k))");
        assertMatchesCPython("grammar_chained_cmp_precedence_and_multi_with",
                "calls = []\n"
                + "def p(v):\n    calls.append(v)\n    return v\n"
                + "print(1 < p(5) < 10, calls)\n"
                + "calls = []\n"
                + "print(10 < p(5) < p(99), calls)\n"
                + "print(0 == 0 == 0, (0 == 0) == 0, 10 > 5 > 2, 1 in [1, 2] == True, not 1 == 2, not 2 == True, not 1 in [2, 3])\n"
                + "print(1 | 2 == 3, 1 + 2 * 3 == 7, -2 ** 2)\n"
                + "[1, 2]\n{'a': 1}\n{1, 2}\n"
                + "log = []\n"
                + "class CM:\n    def __init__(self, t):\n        self.t = t\n"
                + "    def __enter__(self):\n        log.append('+' + self.t)\n        return self.t\n"
                + "    def __exit__(self, *a):\n        log.append('-' + self.t)\n        return False\n"
                + "with CM('A') as a, CM('B') as b:\n    log.append(a + b)\n"
                + "print(log)\n"
                + "fns = [lambda x, k=i: x + k for i in range(3)]\n"
                + "print([f(10) for f in fns])");
    }

    @Test
    void testLazyGeneratorsAndExpressionsDifferential() {
        assertMatchesCPython("genexpr_basic_and_next",
                "print(list(x * 2 for x in [1, 2, 3]))\n"
                + "g = (x for x in [1, 2, 3])\n"
                + "print(next(g), next(g), next(g))\n"
                + "try:\n    next(g)\nexcept StopIteration:\n    print('done')");
        assertMatchesCPython("generator_finally_and_return_value",
                "def f():\n    log = []\n    try:\n        yield 1\n        yield 2\n    finally:\n        log.append('cleanup')\n    return log\n"
                + "g = f()\nprint(next(g))\nprint(next(g))\n"
                + "try:\n    next(g)\nexcept StopIteration as e:\n    print('done', e.value)");
        assertMatchesCPython("genexpr_sum_filter_and_closure",
                "print(sum(x for x in range(10) if x % 2 == 0))\n"
                + "def outer():\n    x = 10\n    return (x + y for y in range(3))\n"
                + "print(list(outer()))");
        assertMatchesCPython("generator_lazy_events_and_nested",
                "events = []\n"
                + "def g():\n    for i in range(3):\n        events.append('before')\n        yield i\n        events.append('after')\n"
                + "it = g()\nprint('init:', events)\n"
                + "print('n0:', next(it), events)\n"
                + "print('n1:', next(it), events)\n"
                + "print('rest:', list(it), events)\n"
                + "print('nested:', list((x + 1 for x in (y * 10 for y in [1, 2, 3]))))\n"
                + "def inf():\n    n = 0\n    while True:\n        yield n\n        n += 1\n"
                + "print('zip_inf:', list(zip(['a', 'b'], inf())))\n"
                + "print('all_inf:', all(x < 2 for x in inf()), 'any_inf:', any(x == 3 for x in inf()))");
        assertMatchesCPython("generator_exception_traceback_metadata",
                "def g():\n    yield 1\n    raise ValueError('boom')\n"
                + "it = g()\nprint(next(it))\n"
                + "try:\n    next(it)\nexcept ValueError as e:\n"
                + "    names = []\n    tb = e.__traceback__\n"
                + "    while tb is not None:\n        names.append(tb.tb_frame.f_code.co_name)\n        tb = tb.tb_next\n"
                + "    print(str(e), tb is None, names)");
    }

    @Test
    void testSetBitwiseAndFrozenSetDifferential() {
        assertMatchesCPython("set_bitwise_and_comparisons",
                "a = {1, 2, 3}\nb = {3.0, 4, 5}\nfs = frozenset([2, 3, 6])\n"
                + "print(sorted(a | b), (a & b) == {3}, sorted(a - b), sorted(a ^ b))\n"
                + "print(type(a | fs) is set, type(fs | a) is frozenset)\n"
                + "print({1} < {1, 2}, {1} < {1}, {1} <= {1}, {1} <= {2}, {1} > {2}, {1, 2} >= {1}, {1, 2} <= frozenset([1, 2]))\n"
                + "print({1, 2}.issubset([1, 2, 3]), {1, 2}.issuperset([1.0]), {1, 2}.isdisjoint([3, 4]), sorted({1, 2}.symmetric_difference([2.0, 3])))\n"
                + "c = {1, 2}\nc |= {3}\nc &= {2, 3}\nc ^= {3, 4}\nc -= {4}\nprint(sorted(c))");
        assertMatchesCPython("frozenset_hash_eq_and_methods",
                "f1 = frozenset([1, 2, 3])\nf2 = frozenset([3.0, True, 2])\n"
                + "print(f1 == f2, f1 == {1, 2, 3}, hash(f1) == hash(f2), {f1: 'ok'}[f2], len({f1, f2}))\n"
                + "print(repr(frozenset()), isinstance(f1, frozenset), isinstance(f1, set), f1.copy() is f1)\n"
                + "print(sorted(frozenset(x * 2 for x in range(4))))");
    }
}
