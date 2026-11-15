package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for core built-in functions: all, any, hash, id, repr, vars, dir,
 * callable, getattr, setattr, delattr, enumerate, zip, map, filter, sum.
 */
class BuiltinsTest {

    @Test
    @DisplayName("all(), any() short-circuit on generators and support all iterables")
    void allAndAny() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            it1 = iter([True, False, 99])
            r_all = all(it1)
            after_all = next(it1)

            it2 = iter([False, "yes", 77])
            r_any = any(it2)
            after_any = next(it2)

            def gen_vals():
                yield 1
                yield 0
                yield 2

            checks = [
                r_all == False and after_all == 99,
                r_any == True and after_any == 77,
                all(gen_vals()) == False,
                any(gen_vals()) == True,
                all([]) == True,
                any([]) == False,
                all((1, "x", [0])) == True,
                any({0, 0.0, False}) == False,
                any({0, 1}) == True,
            ]
            ok = all(checks)
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));

        var te = assertThrows(InterpreterException.class, () -> interp.eval("all(123)"));
        assertEquals("TypeError", te.typeName());
        var te2 = assertThrows(InterpreterException.class, () -> interp.eval("any(123)"));
        assertEquals("TypeError", te2.typeName());
    }

    @Test
    @DisplayName("callable, hash, id, repr, vars, dir, getattr, setattr, delattr")
    void introspectionAndAttributes() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Box:
                cls_val = 10
                def __init__(self, x):
                    self.x = x
                def get(self):
                    return self.x

            def fn():
                return 1

            b = Box(42)
            setattr(b, "y", 99)
            assert getattr(b, "y") == 99
            assert hasattr(b, "y")
            delattr(b, "y")
            assert not hasattr(b, "y")
            assert getattr(b, "y", "missing") == "missing"

            setattr(Box, "extra", 7)
            assert Box.extra == 7
            delattr(Box, "extra")
            assert not hasattr(Box, "extra")

            def scope_probe():
                local_a = 1
                local_b = 2
                v = vars()
                d = dir()
                return (v["local_a"], v["local_b"], "local_a" in d and "local_b" in d)

            a1 = [1]
            a2 = [1]
            checks = [
                callable(fn) == True,
                callable(len) == True,
                callable(Box) == True,
                callable(b.get) == True,
                callable(int) == True,
                callable(b) == False,
                callable(123) == False,
                id(a1) == id(a1),
                id(a1) != id(a2),
                hash(1) == hash(1.0) and hash(1) == hash(True),
                repr("hi") == "'hi'",
                repr("it's") == "\\"it's\\"",
                repr("a\\nb") == "'a\\\\nb'",
                repr(ValueError("bad")) == "ValueError('bad')",
                vars(b) == {"x": 42},
                scope_probe() == (1, 2, True),
                "x" in dir(b) and "get" in dir(b) and "cls_val" in dir(b) and "__class__" in dir(b),
                "__name__" in dir(Box) and "cls_val" in dir(Box),
                "append" in dir([]),
                "add" in dir(set()),
            ]
            ok = all(checks)
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));

        for (String bad : new String[]{
                "x = hash([1])",
                "x = vars(123)",
                "x = delattr(123, 'a')",
                "x = delattr(Box(1), 123)",
                "x = setattr(123, 'a', 1)",
                "x = setattr(Box(1), 123, 1)",
        }) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(bad), bad);
            assertTrueTypeOrAttr(e.typeName(), bad);
        }
        var ae = assertThrows(InterpreterException.class, () -> interp.eval("""
            class S:
                pass
            delattr(S(), "missing")
            """));
        assertEquals("AttributeError", ae.typeName());
    }

    private static void assertTrueTypeOrAttr(String actual, String src) {
        if (!"TypeError".equals(actual) && !"AttributeError".equals(actual)) {
            throw new AssertionError("Expected TypeError/AttributeError for: " + src + ", got " + actual);
        }
    }

    @Test
    @DisplayName("enumerate, zip, map, filter, sum support iterables, generators and kwargs")
    void iterableBuiltins() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def gen():
                yield 10
                yield 20
                yield 30

            checks = [
                enumerate(["a", "b"]) == [(0, "a"), (1, "b")],
                enumerate("xy", start=5) == [(5, "x"), (6, "y")],
                enumerate(gen(), 1) == [(1, 10), (2, 20), (3, 30)],
                zip([1, 2, 3], ("a", "b")) == [(1, "a"), (2, "b")],
                zip() == [],
                map(lambda x: x * 2, (1, 2, 3)) == [2, 4, 6],
                map(lambda x: x + 1, gen()) == [11, 21, 31],
                filter(lambda x: x > 15, gen()) == [20, 30],
                filter(None, [0, 1, "", "ok", None, True]) == [1, "ok", True],
                sum(gen()) == 60,
                sum(gen(), start=5) == 65,
                sum([10 ** 30, 10 ** 30]) == 2 * (10 ** 30),
            ]
            ok = all(checks)
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));

        var badKw = assertThrows(InterpreterException.class, () -> interp.eval("x = sum([1], bad=2)"));
        assertEquals("TypeError", badKw.typeName());
        var dupKw = assertThrows(InterpreterException.class, () -> interp.eval("x = enumerate([1], 0, start=1)"));
        assertEquals("TypeError", dupKw.typeName());
    }
}
