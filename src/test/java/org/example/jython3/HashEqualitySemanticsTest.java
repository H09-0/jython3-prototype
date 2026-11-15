package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PySet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dict keys and set elements follow Python's hash/eq contract: 1 == 1.0 == True,
 * container equality ignores key representation, and unhashable values raise TypeError
 * (never a raw Java exception).
 */
class HashEqualitySemanticsTest {

    @Test
    @DisplayName("dict equality and membership use Python key semantics")
    void dictEqualityAndMembership() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            checks = [
                {1: 0} == {1.0: 0},
                {True: 1} == {1: 1},
                {"a": 1} == {"a": 1.0},
                {(1, 2): "t"} == {(1.0, 2): "t"},
                {10 ** 20: 1} == {float(10 ** 20): 1},
                not ({1: 0} != {1.0: 0}),
                {1: 0} != {1: 0, 2: 0},
                1.0 in {1: 0},
                True in {1: 0},
                (2 in {1: 0}) == False,
            ]
            d = {1: "int"}
            d[1.0] = "float"
            d[True] = "bool"
            checks.append(len(d) == 1 and d[1] == "bool")
            lit = {1: "a", 1.0: "b", True: "c"}
            checks.append(len(lit) == 1 and lit[1] == "c")
            ok = False not in checks
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));
    }

    @Test
    @DisplayName("set de-duplication, membership, equality, len and set() builtin")
    void setSemantics() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            checks = [
                len({1, 1.0, True}) == 1,
                len({0, 0.0, False}) == 1,
                len({(1, 2), (1.0, 2)}) == 1,
                1.0 in {1},
                {1, 2} == {2.0, 1.0},
                {0} != {1},
                len(set()) == 0,
                not set(),
                len(set([1, 1.0, 2, True])) == 2,
                sorted(set("abca")) == ["a", "b", "c"],
                isinstance({1}, set),
                len({v % 3 for v in range(10)}) == 3,
            ]
            ok = False not in checks
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));
    }

    @Test
    @DisplayName("unhashable keys/elements raise TypeError, not a Java exception")
    void unhashableRaisesTypeError() {
        var interp = Jython3Interpreter.trusted();
        for (String src : List.of("x = {[1]: 1}", "x = {[1]}", "x = {{1}: 1}", "x = {{}}",
                                  "x = [1] in {1: 0}", "x = [1] in {1}", "x = set([[1]])")) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(src), src);
            assertEquals("TypeError", e.typeName(), src);
        }
    }

    @Test
    @DisplayName("PySet Java API keeps the first-inserted representative and rejects unhashables")
    void pySetJavaApi() {
        var s = new PySet();
        s.add(PyInt.of(1));
        s.add(new PyFloat(1.0));
        s.add(PyBool.TRUE);
        assertEquals(1, s.size());
        assertEquals(PyInt.of(1), s.items().iterator().next());
        assertTrue(s.contains(new PyFloat(1.0)));
        assertFalse(s.contains(PyInt.of(2)));
        assertThrows(InterpreterException.class, () -> s.add(new PyList(new java.util.ArrayList<>())));
        assertThrows(UnsupportedOperationException.class, () -> s.items().clear(), "items() is read-only");
    }
}
