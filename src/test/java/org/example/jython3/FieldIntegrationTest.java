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
}
