package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for set & frozenset bitwise operators (|, &, -, ^, |=, &=, -=, ^=),
 * partial-order subset/superset comparisons (<, <=, >, >=), set methods, and
 * frozenset immutability/hashability.
 */
class SetOperatorsAndFrozenSetTest {

    @Test
    @DisplayName("Set bitwise operators, compound assignments, partial comparisons, and methods")
    void setOperatorsAndComparisons() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            a = {1, 2, 3}
            b = {3.0, 4, 5}
            fs = frozenset([2, 3, 6])

            # Binary operators preserve left operand's set/frozenset type
            u1 = a | b
            u2 = a | fs
            u3 = fs | a
            i1 = a & b
            i2 = fs & a
            d1 = a - b
            d2 = fs - a
            x1 = a ^ b
            x2 = fs ^ a

            # Compound assignments mutate mutable sets in-place (preserving aliases!)
            c = {1, 2}
            c_alias = c
            c |= {2.0, 3}
            assert c is c_alias and c_alias == {1, 2, 3}
            c &= {2, 3, 4}
            assert c is c_alias and c_alias == {2, 3}
            c ^= {3, 5}
            assert c is c_alias and c_alias == {2, 5}
            c -= {5}
            assert c is c_alias and c_alias == {2}

            # Self-aliasing augmented assignment on set & list
            s_self = {1, 2}
            s_self |= s_self
            assert s_self == {1, 2}
            s_self &= s_self
            assert s_self == {1, 2}
            s_self -= s_self
            assert s_self == set()
            s_self2 = {1, 2}
            s_self2 ^= s_self2
            assert s_self2 == set()

            lst = [1, 2]
            lst_alias = lst
            lst += (3, 4)
            lst += (x * 10 for x in [5])
            assert lst is lst_alias and lst_alias == [1, 2, 3, 4, 50]
            lst *= 2
            assert lst is lst_alias and lst_alias == [1, 2, 3, 4, 50, 1, 2, 3, 4, 50]
            lst += lst
            assert len(lst_alias) == 20

            cf = frozenset([1, 2])
            cf_alias = cf
            cf |= frozenset([3])
            assert cf is not cf_alias and cf_alias == frozenset([1, 2])

            # Partial-order subset / superset comparisons
            checks = [
                type(u1) is set and u1 == {1, 2, 3, 4, 5},
                type(u2) is set and u2 == {1, 2, 3, 6},
                type(u3) is frozenset and u3 == frozenset([1, 2, 3, 6]),
                i1 == {3} and type(i2) is frozenset and i2 == frozenset([2, 3]),
                d1 == {1, 2} and type(d2) is frozenset and d2 == frozenset([6]),
                x1 == {1, 2, 4, 5} and type(x2) is frozenset and x2 == frozenset([1, 6]),
                c == {2},
                cf == frozenset([1, 2, 3]),
                ({1} < {1, 2}) == True,
                ({1} < {1}) == False,
                ({1} <= {1}) == True,
                ({1} <= {2}) == False,
                ({1} > {2}) == False,
                ({1, 2} > {1}) == True,
                ({1, 2} >= {1, 2}) == True,
                ({1, 2} <= frozenset([1, 2, 3])) == True,
                (frozenset([1, 2]) < {1, 2, 3}) == True,
                {1, 2}.symmetric_difference([2.0, 3]) == {1, 3},
                {1, 2}.issubset([1, 2, 3]) == True,
                {1, 2}.issuperset([1.0]) == True,
                {1, 2}.isdisjoint([3, 4]) == True,
                {1, 2}.isdisjoint([2.0, 4]) == False,
            ]
            ok = all(checks)
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));

        // Invalid operand types raise TypeError
        for (String bad : List.of(
                "x = {1} | [2]",
                "x = {1} & [2]",
                "x = {1} - [2]",
                "x = {1} ^ [2]",
                "s = {1}\ns |= [2]",
                "s = {1}\ns &= [2]",
                "s = {1}\ns -= [2]",
                "s = {1}\ns ^= [2]",
                "x = {1} < [1, 2]",
                "x = {1} <= [1, 2]",
                "x = {1} > [1]",
                "x = {1} >= [1]",
                "x = frozenset([1]) | {2} | [3]"
        )) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(bad), bad);
            assertEquals("TypeError", e.typeName(), bad);
        }
    }

    @Test
    @DisplayName("frozenset is hashable, immutable, and works as dict keys / set elements")
    void frozenSetHashabilityAndImmutability() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            f1 = frozenset([1, 2, 3])
            f2 = frozenset([3.0, True, 2])
            f_empty = frozenset()
            f_gen = frozenset(x * 2 for x in range(3))

            d = {f1: "found"}
            outer_set = {f1, f2, frozenset(["a", "b"])}

            checks = [
                f1 == f2,
                f1 == {1, 2, 3},
                hash(f1) == hash(f2),
                d[f2] == "found",
                f2 in d,
                len(outer_set) == 2,
                len(f_empty) == 0,
                not f_empty,
                repr(f_empty) == "frozenset()",
                f_gen == frozenset([0, 2, 4]),
                type(f1) is frozenset,
                isinstance(f1, frozenset),
                not isinstance(f1, set),
                list(frozenset(["x"])) == ["x"],
                f1.copy() is f1,
                f1.issubset([1, 2, 3, 4]),
                f1.issuperset([1, 2]),
                f1.isdisjoint([4, 5]),
                f1.union([4]) == frozenset([1, 2, 3, 4]),
                f1.intersection([2, 3, 9]) == frozenset([2, 3]),
                f1.difference([1]) == frozenset([2, 3]),
                f1.symmetric_difference([3, 4]) == frozenset([1, 2, 4]),
            ]
            ok = all(checks)
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));

        // Unhashable elements rejected on frozenset creation
        var ue = assertThrows(InterpreterException.class, () -> interp.eval("frozenset([[1]])"));
        assertEquals("TypeError", ue.typeName());
        var ue2 = assertThrows(InterpreterException.class, () -> interp.eval("frozenset([{1}])"));
        assertEquals("TypeError", ue2.typeName());

        // Mutating methods absent on frozenset
        for (String bad : List.of("frozenset([1]).add(2)", "frozenset([1]).remove(1)", "frozenset([1]).clear()")) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(bad), bad);
            assertEquals("AttributeError", e.typeName(), bad);
        }
    }
}
