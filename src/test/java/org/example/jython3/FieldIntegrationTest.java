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
                "Unreferenced local variable must not be retained in function closure");
        assertTrue(lamObj.closure().containsKey("used_var"));
        org.junit.jupiter.api.Assertions.assertFalse(lamObj.closure().containsKey("unused_heavy"),
                "Unreferenced local variable must not be retained in lambda closure");
    }
}
