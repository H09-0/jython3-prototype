package org.example.jython3;

import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyStr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests derived from real-world field testing scenarios:
 * - Nested JSON parsing/serialization
 * - Zero-argument super() across multi-level inheritance
 * - Raw string literals (r"...", r'...', r"""...""")
 * - Java StringBuilder/StringBuffer fluent method chaining
 * - Built-in functions (list, sum, sorted, reversed) consuming generators/iterators
 */
public class FieldIntegrationTest {

    @Test
    @DisplayName("json.loads recursively parses nested objects, arrays, numbers, and escapes")
    void testNestedJsonParsing() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            import json
            payload = '{"id": 101, "items": [{"price": 25.5, "qty": 2}, {"price": 10.0, "qty": 1}], "tags": ["a:b", "c\\\\nd"], "meta": {"ok": true, "err": null}}'
            data = json.loads(payload)
            total = sum([item["price"] * item["qty"] for item in data["items"]])
            tag0 = data["tags"][0]
            ok_flag = data["meta"]["ok"]
            err_val = data["meta"]["err"]
            """);
        assertEquals(61.0, ((org.example.jython3.runtime.PyFloat) interp.getGlobal("total")).value(), 1e-6);
        assertEquals(new PyStr("a:b"), interp.getGlobal("tag0"));
        assertEquals(PyBool.TRUE, interp.getGlobal("ok_flag"));
        assertTrue(interp.getGlobal("err_val") instanceof org.example.jython3.runtime.PyNull);
    }

    @Test
    @DisplayName("json.loads rejects malformed JSON with JSONDecodeError (subclass of ValueError)")
    void testInvalidJsonRaisesJSONDecodeError() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            import json
            bad_inputs = ["{bad json}", "[1,]", "unknown", "", "   ", '{"a": 1,}', '{"a" 1}', '{"a": }', "123 extra", "01"]
            caught_decode_err = 0
            caught_value_err = 0
            for s in bad_inputs:
                try:
                    json.loads(s)
                except json.JSONDecodeError:
                    caught_decode_err = caught_decode_err + 1
                try:
                    json.loads(s)
                except ValueError:
                    caught_value_err = caught_value_err + 1
            """);
        assertEquals(new PyInt(10), interp.getGlobal("caught_decode_err"));
        assertEquals(new PyInt(10), interp.getGlobal("caught_value_err"));
    }

    @Test
    @DisplayName("Zero-arg super() resolves parent methods across multi-level inheritance")
    void testZeroArgSuperInheritance() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Base:
                def __init__(self, name):
                    self.name = name
                def describe(self):
                    return "Base:" + self.name

            class Mid(Base):
                def __init__(self, name, role):
                    super().__init__(name)
                    self.role = role
                def describe(self):
                    return super().describe() + ":" + self.role

            class Leaf(Mid):
                pass

            obj = Leaf("core", "engine")
            res = obj.describe()
            """);
        assertEquals(new PyStr("Base:core:engine"), interp.getGlobal("res"));
    }

    @Test
    @DisplayName("Raw string literals r'...' and r\"...\" preserve backslashes literally")
    void testRawStringLiterals() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            import re
            pattern = r"\\d+"
            nums = re.findall(pattern, "Order 42 has 15 items")
            win_path = r'C:\\Users\\test\\new_folder'
            """);
        PyList nums = (PyList) interp.getGlobal("nums");
        assertEquals(2, nums.items().size());
        assertEquals("42", nums.items().get(0).toString());
        assertEquals("15", nums.items().get(1).toString());
        assertEquals("C:\\Users\\test\\new_folder", interp.getGlobal("win_path").toString());
    }

    @Test
    @DisplayName("StringBuilder fluent method chaining works across JavaBridge")
    void testStringBuilderFluentChaining() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            from java.lang import StringBuilder
            sb = StringBuilder()
            res = sb.append("Jython").append("-").append(3).toString()
            """);
        assertEquals(new PyStr("Jython-3"), interp.getGlobal("res"));
    }

    @Test
    @DisplayName("list(), sum(), sorted(), reversed() consume generators and iterators")
    void testBuiltinsConsumeGenerators() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def gen(n):
                for i in range(n):
                    yield i * 2

            as_list = list(gen(4))
            as_sum  = sum(gen(4))
            as_rev  = reversed(gen(4))
            as_sort = sorted(gen(4), reverse=True)
            """);
        assertEquals("[0, 2, 4, 6]", interp.getGlobal("as_list").toString());
        assertEquals(new PyInt(12), interp.getGlobal("as_sum"));
        assertEquals("[6, 4, 2, 0]", interp.getGlobal("as_rev").toString());
        assertEquals("[6, 4, 2, 0]", interp.getGlobal("as_sort").toString());
    }

    @Test
    @DisplayName("re.search and re.findall raise re.error on invalid regex and TypeError on invalid args")
    void testReErrorHandling() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            import re
            caught_pat = False
            try:
                re.search(r"[unclosed", "abc")
            except re.error:
                caught_pat = True

            caught_type = False
            try:
                re.findall(123, "abc")
            except TypeError:
                caught_type = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("caught_pat"));
        assertEquals(PyBool.TRUE, interp.getGlobal("caught_type"));
    }

    @Test
    @DisplayName("json.dumps serializes BigInt as unquoted JSON number and round-trips via json.loads")
    void testJsonDumpsBigInt() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            import json
            big = 10 ** 30
            dumped = json.dumps(big)
            loaded = json.loads(dumped)
            nested_dumped = json.dumps({"big": big, "tuple": (1, 2)})
            nested_loaded = json.loads(nested_dumped)
            """);
        assertEquals(new PyStr("1000000000000000000000000000000"), interp.getGlobal("dumped"));
        assertEquals(new org.example.jython3.runtime.BigInt(new java.math.BigInteger("1000000000000000000000000000000")),
                interp.getGlobal("loaded"));
        assertEquals(new PyStr("{\"big\":1000000000000000000000000000000,\"tuple\":[1,2]}"),
                interp.getGlobal("nested_dumped"));
    }

    @Test
    @DisplayName("json.dumps detects circular references in lists and dicts while allowing shared siblings")
    void testJsonDumpsCircularReferenceDetection() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            import json

            # Shared non-circular sibling is valid JSON
            shared = [1, 2]
            ok_Sibling = json.dumps([shared, shared])

            # Circular list raises ValueError
            circ_list = [1]
            circ_list.append(circ_list)
            caught_list = False
            try:
                json.dumps(circ_list)
            except ValueError:
                caught_list = True

            # Circular dict raises ValueError
            circ_dict = {"a": 1}
            circ_dict["self"] = circ_dict
            caught_dict = False
            try:
                json.dumps(circ_dict)
            except ValueError:
                caught_dict = True
            """);
        assertEquals(new PyStr("[[1,2],[1,2]]"), interp.getGlobal("ok_Sibling"));
        assertEquals(PyBool.TRUE, interp.getGlobal("caught_list"));
        assertEquals(PyBool.TRUE, interp.getGlobal("caught_dict"));
    }

    @Test
    @DisplayName("math functions accept bool and BigInt arguments and raise OverflowError on huge BigInt")
    void testMathBoolAndBigIntSupport() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            import math
            s_true  = math.sqrt(True)
            s_false = math.sqrt(False)
            s_big   = math.sqrt(10 ** 30)
            a_big   = math.abs(-(10 ** 30))
            fl_big  = math.floor(10 ** 30)
            cl_big  = math.ceil(10 ** 30)

            huge = 10 ** 400
            overflow_count = 0
            for fn in [math.sqrt, math.sin, math.cos, math.tan, math.log]:
                try:
                    fn(huge)
                except OverflowError:
                    overflow_count = overflow_count + 1

            try:
                math.pow(huge, 2)
            except OverflowError:
                overflow_count = overflow_count + 1
            """);
        var expectedBig = new org.example.jython3.runtime.BigInt(new java.math.BigInteger("1000000000000000000000000000000"));
        assertEquals(1.0, ((org.example.jython3.runtime.PyFloat) interp.getGlobal("s_true")).value(), 1e-9);
        assertEquals(0.0, ((org.example.jython3.runtime.PyFloat) interp.getGlobal("s_false")).value(), 1e-9);
        assertEquals(1e15, ((org.example.jython3.runtime.PyFloat) interp.getGlobal("s_big")).value(), 1e2);
        assertEquals(expectedBig, interp.getGlobal("a_big"));
        assertEquals(expectedBig, interp.getGlobal("fl_big"));
        assertEquals(expectedBig, interp.getGlobal("cl_big"));
        assertEquals(new PyInt(6), interp.getGlobal("overflow_count"));
    }

    @Test
    @DisplayName("All modular FieldScenario workloads execute and verify in JUnit")
    void testAllModularFieldScenarios() {
        var scenarios = java.util.List.of(
                new org.example.jython3.field.AnalyticsScenario(),
                new org.example.jython3.field.OopScenario(),
                new org.example.jython3.field.RegexScenario(),
                new org.example.jython3.field.JavaBridgeScenario()
        );
        for (var sc : scenarios) {
            var interp = Jython3Interpreter.trusted();
            interp.eval(sc.pythonCode(), "<" + sc.name() + ">");
            sc.verify(interp);
        }
    }

    @Test
    @DisplayName("Unified callable expression trailers: factory()(10), items[0](10), obj.method()(10), and non-callable TypeError")
    void testUnifiedCallableExpressionTrailers() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def factory():
                return lambda x: x + 5

            items = [lambda x: x * 3, str]

            class Dispatcher:
                def method(self):
                    return lambda x: x - 2

            log = []
            def get_callback():
                return lambda v: log.append("cb:" + v)

            obj = Dispatcher()
            r1 = factory()(10)
            r2 = items[0](10)
            r3 = items[1](99)
            r4 = obj.method()(10)
            get_callback()("value")

            not_callable_err = ""
            try:
                x = 42
                x(10)
            except TypeError as e:
                not_callable_err = str(e)
            """);
        assertEquals(new PyInt(15), interp.getGlobal("r1"));
        assertEquals(new PyInt(30), interp.getGlobal("r2"));
        assertEquals(new PyStr("99"), interp.getGlobal("r3"));
        assertEquals(new PyInt(8), interp.getGlobal("r4"));
        assertEquals("['cb:value']", interp.getGlobal("log").toString());
        assertTrue(interp.getGlobal("not_callable_err").toString().contains("'int' object is not callable"),
                interp.getGlobal("not_callable_err").toString());
    }

    @Test
    @DisplayName("print is a first-class built-in function supporting assignment, lists, closures, sep/end, and type validation")
    void testPrintAsFirstClassBuiltin() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Sink:
                def __init__(self):
                    self.buf = ""
                def write(self, s):
                    self.buf = self.buf + s

            sink = Sink()
            print("a", "b", sep=",", file=sink)
            print("loading", end="", file=sink)

            fn = print
            fn("...", file=sink)

            printers = [print]
            printers[0]("done", end="!", file=sink)

            def make_logger(p):
                return lambda msg: p(msg, end="", file=sink)

            logger = make_logger(print)
            ret_val = logger("ok")

            bad_sep = False
            try:
                print("x", sep=123)
            except TypeError:
                bad_sep = True

            bad_end = False
            try:
                print("x", end=456)
            except TypeError:
                bad_end = True

            bad_kw = False
            try:
                print("x", unknown=1)
            except TypeError:
                bad_kw = True

            out_text = sink.buf
            """);
        assertEquals(new PyStr("a,b\nloading...\ndone!ok"), interp.getGlobal("out_text"));
        assertTrue(interp.getGlobal("ret_val") instanceof org.example.jython3.runtime.PyNull);
        assertEquals(PyBool.TRUE, interp.getGlobal("bad_sep"));
        assertEquals(PyBool.TRUE, interp.getGlobal("bad_end"));
        assertEquals(PyBool.TRUE, interp.getGlobal("bad_kw"));
    }

    @Test
    @DisplayName("Unified LValue: compound assignment on vars/attrs/subscripts/nested, single evaluation, unpacking, del, and for-unpacking")
    void testUnifiedLValueAndCompoundAssignment() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class CounterBox:
                def __init__(self, v):
                    self.value = v
                    self.temp = 999

            x = 10
            x += 5

            obj = CounterBox(20)
            obj.value += 7

            items = [10, 20, 30]
            items[0] += 5

            matrix = [[1, 2], [3, 4]]
            matrix[0][1] *= 10

            # Verify target expressions are evaluated ONLY ONCE in augmented assignment
            eval_counts = {"obj": 0, "mat": 0, "idx": 0}
            def get_obj():
                eval_counts["obj"] += 1
                return obj
            def get_row():
                eval_counts["mat"] += 1
                return matrix[1]
            def get_idx():
                eval_counts["idx"] += 1
                return 0

            get_obj().value += 3
            get_row()[get_idx()] += 100

            # Unified LValue in tuple unpacking, chained assignment, del, and for-loop unpacking
            obj.value, items[2] = 50, 60
            a = b = c = items[1] = 77

            del obj.temp
            has_temp = hasattr(obj, "temp")

            pair_sum = 0
            for k, v in [(1, 10), (2, 20), (3, 30)]:
                pair_sum += k * v

            # Immutable target fails cleanly with TypeError
            imm_failed = False
            t = (1, 2, 3)
            try:
                t[0] += 10
            except TypeError:
                imm_failed = True
            """);

        assertEquals(new PyInt(15), interp.getGlobal("x"));
        var obj = (org.example.jython3.runtime.PyInstance) interp.getGlobal("obj");
        assertEquals(new PyInt(50), obj.getField("value"));
        assertEquals("[15, 77, 60]", interp.getGlobal("items").toString());
        assertEquals("[[1, 20], [103, 4]]", interp.getGlobal("matrix").toString());

        var evalCounts = (org.example.jython3.runtime.PyDict) interp.getGlobal("eval_counts");
        assertEquals(new PyInt(1), evalCounts.get(new PyStr("obj")));
        assertEquals(new PyInt(1), evalCounts.get(new PyStr("mat")));
        assertEquals(new PyInt(1), evalCounts.get(new PyStr("idx")));

        assertEquals(new PyInt(77), interp.getGlobal("a"));
        assertEquals(new PyInt(77), interp.getGlobal("b"));
        assertEquals(new PyInt(77), interp.getGlobal("c"));
        assertEquals(PyBool.FALSE, interp.getGlobal("has_temp"));
        assertEquals(new PyInt(140), interp.getGlobal("pair_sum"));
        assertEquals(PyBool.TRUE, interp.getGlobal("imm_failed"));
    }

    @Test
    @DisplayName("Closures and lambdas capture only referenced enclosing variables")
    void testSelectiveClosureCapture() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def make_closures():
                used_var = 42
                unused_heavy = [1, 2, 3, 4, 5]
                def inner_fn(x):
                    msg = "unused_heavy"
                    return x + used_var
                lam = lambda y: y * used_var
                return inner_fn, lam

            fn_obj, lam_obj = make_closures()
            r_fn = fn_obj(8)
            r_lam = lam_obj(2)
            """);
        assertEquals(new PyInt(50), interp.getGlobal("r_fn"));
        assertEquals(new PyInt(84), interp.getGlobal("r_lam"));

        var fnObj = (org.example.jython3.runtime.PyFunction) interp.getGlobal("fn_obj");
        var lamObj = (org.example.jython3.runtime.PyFunction) interp.getGlobal("lam_obj");
        assertTrue(fnObj.closure().containsKey("used_var"));
        org.junit.jupiter.api.Assertions.assertFalse(fnObj.closure().containsKey("unused_heavy"),
                "Unreferenced local variable must not be retained even when its name appears as a string literal");
        assertTrue(lamObj.closure().containsKey("used_var"));
        org.junit.jupiter.api.Assertions.assertFalse(lamObj.closure().containsKey("unused_heavy"),
                "Unreferenced local variable must not be retained in lambda closure");
    }

    @Test
    @DisplayName("Bitwise operations (&, |, ^, ~, <<, >>) and compound assignments (&=, |=, ^=, <<=, >>=) with PyInt, BigInt, and float rejection")
    void testBitwiseOperations() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            assert (5 & 3) == 1
            assert (5 | 2) == 7
            assert (5 ^ 1) == 4
            assert (~5) == -6
            assert (1 << 4) == 16
            assert (16 >> 2) == 4

            # BigInt bitwise & shift
            big = 1 << 80
            big |= 0xFF
            big ^= 0x0F
            big &= 0xF0
            assert big == 240

            # Compound bitwise on attributes and nested subscripts
            class Flags:
                def __init__(self, m):
                    self.mask = m

            f = Flags(1)
            f.mask <<= 4
            f.mask |= 3

            arr = [[8, 16]]
            arr[0][1] >>= 2
            arr[0][0] ^= 3

            float_err = False
            try:
                bad = 3.5 | 1
            except TypeError:
                float_err = True

            inv_float_err = False
            try:
                bad2 = ~1.5
            except TypeError:
                inv_float_err = True
            """);
        var f = (org.example.jython3.runtime.PyInstance) interp.getGlobal("f");
        assertEquals(new PyInt(19), f.getField("mask"));
        assertEquals("[[11, 4]]", interp.getGlobal("arr").toString());
        assertEquals(PyBool.TRUE, interp.getGlobal("float_err"));
        assertEquals(PyBool.TRUE, interp.getGlobal("inv_float_err"));
    }

    @Test
    @DisplayName("Comprehensive try/finally control-flow exits (normal, return, break, continue, raise, except, nested, closures, Java Bridge)")
    void testTryFinallyControlFlowComprehensive() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            # 1. Normal completion
            ev1 = []
            try:
                ev1.append("try")
            finally:
                ev1.append("finally")
            assert ev1 == ["try", "finally"]

            # 2. return inside try
            ev2 = []
            def f2():
                try:
                    return 10
                finally:
                    ev2.append("finally")
            r2 = f2()
            assert r2 == 10 and ev2 == ["finally"]

            # 3. return inside finally overrides try return
            def f3():
                try:
                    return 10
                finally:
                    return 20
            assert f3() == 20

            # 4 & 6. raise inside try with finally (unhandled exception propagates after finally)
            ev4 = []
            caught4 = False
            try:
                try:
                    raise ValueError("x")
                finally:
                    ev4.append("finally")
            except ValueError:
                caught4 = True
            assert caught4 and ev4 == ["finally"]

            # 5. handled exception with finally
            ev5 = []
            try:
                raise ValueError("x")
            except ValueError:
                ev5.append("except")
            finally:
                ev5.append("finally")
            assert ev5 == ["except", "finally"]

            # 7. break inside try
            ev7 = []
            for i in range(3):
                try:
                    ev7.append(i)
                    break
                finally:
                    ev7.append("finally")
            assert ev7 == [0, "finally"]

            # 8. continue inside try
            ev8 = []
            for i in range(3):
                try:
                    if i == 1:
                        continue
                    ev8.append(i)
                finally:
                    ev8.append("finally")
            assert ev8 == [0, "finally", "finally", 2, "finally"]

            # 9. nested finally with raise
            ev9 = []
            caught9 = False
            try:
                try:
                    try:
                        ev9.append("inner")
                        raise ValueError("x")
                    finally:
                        ev9.append("inner-finally")
                finally:
                    ev9.append("outer-finally")
            except ValueError:
                caught9 = True
            assert caught9 and ev9 == ["inner", "inner-finally", "outer-finally"]

            # 10. return with nested finally
            ev10 = []
            def f10():
                try:
                    try:
                        return 1
                    finally:
                        ev10.append("inner")
                finally:
                    ev10.append("outer")
            r10 = f10()
            assert r10 == 1 and ev10 == ["inner", "outer"]

            # 11. loop inside try (and exception inside for-loop inside try)
            ev11 = []
            try:
                for i in range(3):
                    ev11.append(i)
            finally:
                ev11.append("done")
            assert ev11 == [0, 1, 2, "done"]

            # 12 & 13. exception inside finally replaces original exception
            caught13 = ""
            try:
                try:
                    raise RuntimeError("outer")
                finally:
                    raise ValueError("inner-override")
            except ValueError as e:
                caught13 = str(e)
            assert "inner-override" in caught13

            # 14 & 15. break and continue inside finally
            ev14 = []
            for i in range(3):
                try:
                    raise ValueError("suppressed-by-break")
                finally:
                    ev14.append(i)
                    break
            assert ev14 == [0]

            ev15 = []
            for i in range(3):
                try:
                    raise ValueError("suppressed-by-continue")
                finally:
                    ev15.append(i)
                    continue
            assert ev15 == [0, 1, 2]

            # 16. return, break, and continue inside except with finally
            ev16 = []
            def f16():
                try:
                    raise KeyError("k")
                except KeyError:
                    ev16.append("in-except")
                    return 77
                finally:
                    ev16.append("in-finally")
            assert f16() == 77 and ev16 == ["in-except", "in-finally"]

            for i in range(3):
                try:
                    raise KeyError("k")
                except KeyError:
                    ev16.append("loop-except")
                    break
                finally:
                    ev16.append("loop-finally")
            assert ev16 == ["in-except", "in-finally", "loop-except", "loop-finally"]

            # 17 & 18. nested functions & closures inside try/finally
            def f18():
                state = 10
                readers = []
                try:
                    def read_state():
                        return state
                    readers.append(read_state)
                    state = 20
                    return state, readers[0]
                finally:
                    state = 30
            ret_state, reader_fn = f18()
            # return expression evaluated before finally (20), while closure sees updated binding if nonlocal/cell or frame state
            assert ret_state == 20

            # Java Bridge exception inside try/finally
            from java.util import ArrayList
            j_ev = []
            j_caught = False
            try:
                try:
                    j_list = ArrayList()
                    x = j_list.get(99)
                finally:
                    j_ev.append("java-finally")
            except Exception:
                j_caught = True
            assert j_caught and j_ev == ["java-finally"]

            all_passed = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("all_passed"));

        // Verify break and continue outside loop raise SyntaxError
        org.junit.jupiter.api.Assertions.assertThrows(
                org.example.jython3.runtime.InterpreterException.class,
                () -> interp.eval("break"));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.example.jython3.runtime.InterpreterException.class,
                () -> interp.eval("continue"));
    }

    @Test
    @DisplayName("try/finally edge cases: with-exits, else raise, synthetic-name isolation, context, frame hygiene")
    void testTryFinallyEdgeCasesAndSyntheticNameIsolation() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class CM:
                def __init__(self, log, tag):
                    self.log = log
                    self.tag = tag
                def __enter__(self):
                    self.log.append("enter-" + self.tag)
                    return self
                def __exit__(self, t, v, tb):
                    self.log.append("exit-" + self.tag)
                    return False

            # 1. return inside with runs __exit__ and outer finally, in order
            def w_return(log):
                try:
                    with CM(log, "a"):
                        return 7
                finally:
                    log.append("finally")
            wl = []
            assert w_return(wl) == 7
            assert wl == ["enter-a", "exit-a", "finally"], wl

            # 2. break / continue inside with run __exit__ every iteration
            bl = []
            for i in range(3):
                with CM(bl, str(i)):
                    if i == 0:
                        continue
                    if i == 1:
                        break
            assert bl == ["enter-0", "exit-0", "enter-1", "exit-1"], bl

            # 3. nested with + for + return keeps iterator stack clean
            def w_nested(log):
                for a in [1, 2]:
                    with CM(log, "o"):
                        for b in [10, 20]:
                            with CM(log, "i"):
                                if b == 20:
                                    return a + b
            nl = []
            assert w_nested(nl) == 21
            assert nl == ["enter-o", "enter-i", "exit-i", "enter-i", "exit-i", "exit-o"], nl

            # 4. exception raised in else still runs finally and propagates
            el = []
            try:
                try:
                    el.append("try")
                except KeyError:
                    el.append("except")
                else:
                    el.append("else")
                    raise ValueError("from-else")
                finally:
                    el.append("finally")
            except ValueError as ex:
                el.append("outer:" + str(ex))
            assert el == ["try", "else", "finally", "outer:from-else"], el

            # 5. return inside finally while 'except ... as e' is active cancels nothing outside
            def r_in_finally_with_as():
                try:
                    try:
                        raise KeyError("k")
                    except KeyError as e:
                        raise ValueError("v")
                    finally:
                        return "fin-wins"
                except Exception:
                    return "should-not-propagate"
            assert r_in_finally_with_as() == "fin-wins"
            # the 'as' target is unbound after the handler
            def as_cleanup():
                try:
                    raise KeyError("k")
                except KeyError as e:
                    pass
                try:
                    return e
                except NameError:
                    return "unbound"
            assert as_cleanup() == "unbound"

            # 6. user names that match legacy temporary prefixes are not clobbered
            def user_ret_name():
                __ret_val_0 = 42
                seen = []
                def inner():
                    try:
                        return 1
                    finally:
                        seen.append("x")
                try:
                    return inner() + 0
                finally:
                    seen.append(__ret_val_0)
            assert user_ret_name() == 1

            def user_ret_name_visible():
                __ret_val_0 = 42
                out = []
                try:
                    return 1
                finally:
                    out.append(__ret_val_0)
                    assert out == [42], out

            assert user_ret_name_visible() == 1

            __comp_list_0 = "user-module-value"
            squares = [x * x for x in range(4)]
            assert squares == [0, 1, 4, 9]
            assert __comp_list_0 == "user-module-value"

            def comp_names():
                __comp_list_0 = "fn"
                __comp_dict_1 = "fd"
                __comp_set_2 = "fs"
                a = [i for i in range(3)]
                b = {i: i for i in range(2)}
                c = {i for i in range(2)}
                return (__comp_list_0, __comp_dict_1, __comp_set_2, a, b, c)
            assert comp_names() == ("fn", "fd", "fs", [0, 1, 2], {0: 0, 1: 1}, {0, 1})

            # set value equality (regression uncovered by the assertion above)
            assert {0, 1} == {1, 0}
            assert not ({0, 1} != {1, 0})
            assert {0} != {1}
            assert {1, 2} != {1}
            assert {1} == {1.0}
            assert ({"a"},) == ({"a"},)

            # nested comprehensions inside lambdas inside comprehensions keep independent accumulators
            fns = [lambda n=k: [j + n for j in range(2)] for k in range(3)]
            assert [f() for f in fns] == [[0, 1], [1, 2], [2, 3]]

            # 7. Java exception inside try/finally in a hot loop leaves frame/stack clean
            from java.util import ArrayList
            jl = ArrayList()
            fin = 0
            caught = 0
            for _ in range(500):
                try:
                    try:
                        total = 1 + (2 * jl.get(5))
                    finally:
                        fin += 1
                except Exception:
                    caught += 1
            assert fin == 500 and caught == 500
            assert [1, 2, 3][1] + (4 * 5) == 22

            edge_ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("edge_ok"));

        // Synthetic temporaries never leak into globals after successful runs.
        interp.eval("leak_probe = [v for v in range(3)]");
        for (String name : new String[]{"$comp_list_0", "$ret_val_0"}) {
            org.junit.jupiter.api.Assertions.assertNull(interp.getGlobal(name), name + " leaked");
        }

        // __context__ is recorded when finally raises while an exception is pending.
        var ie = org.junit.jupiter.api.Assertions.assertThrows(
                org.example.jython3.runtime.InterpreterException.class,
                () -> interp.eval("""
                    try:
                        raise KeyError("first")
                    finally:
                        raise ValueError("second")
                    """));
        var pe = org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.example.jython3.runtime.PyException.class, ie.value());
        assertEquals("ValueError", pe.typeName());
        org.junit.jupiter.api.Assertions.assertNotNull(pe.context(), "finally-raised exception keeps pending one as __context__");
        assertEquals("KeyError", pe.context().typeName());

        // break/continue outside a loop surface as SyntaxError.
        var se = org.junit.jupiter.api.Assertions.assertThrows(
                org.example.jython3.runtime.InterpreterException.class,
                () -> interp.eval("break"));
        assertEquals("SyntaxError", se.typeName());
    }

    @Test
    @DisplayName("Re-entrant Python calls (__init__/__enter__/__exit__) never return into or unwind the caller's frames")
    void testReentrantCallsPreserveCallerFrame() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class CM:
                def __init__(self, log):
                    self.log = log
                def __enter__(self):
                    self.log.append("enter")
                    return 5
                def __exit__(self, t, v, tb):
                    self.log.append("exit:" + ("KeyError" if isinstance(v, KeyError) else str(t)))
                    return False

            # with inside a function: body, exit and code after the block all run
            def plain(log):
                log.append("start")
                with CM(log) as v:
                    log.append("body" + str(v))
                log.append("end")
                return "done"
            pl = []
            assert plain(pl) == "done", pl
            assert pl == ["start", "enter", "body5", "exit:None", "end"], pl

            # with inside nested function calls (depth > 1 on the call stack)
            def outer(log):
                return inner(log) + 1
            def inner(log):
                with CM(log):
                    return 10
            ol = []
            assert outer(ol) == 11, ol
            assert ol == ["enter", "exit:None"], ol

            # exception inside with inside a function propagates to the caller's handler
            def raising(log):
                with CM(log):
                    raise KeyError("boom")
            rl = []
            try:
                raising(rl)
                rl.append("not-reached")
            except KeyError:
                rl.append("caught")
            assert rl == ["enter", "exit:KeyError", "caught"], rl

            # exception raised by __init__ called from a function is catchable in that function
            class Bad:
                def __init__(self):
                    raise ValueError("init failed")
            def make_bad():
                try:
                    Bad()
                    return "no"
                except ValueError:
                    return "caught-in-caller"
            assert make_bad() == "caught-in-caller"

            # caller locals are intact after a re-entrant call that raised
            def locals_intact():
                a = 1
                b = [2]
                try:
                    Bad()
                except ValueError:
                    pass
                return a + b[0]
            assert locals_intact() == 3

            reentrant_ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("reentrant_ok"));
    }
}
