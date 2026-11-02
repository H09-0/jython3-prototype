package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Milestone 1 (M1) Conformance & Acceptance Gate.
 * <p>
 * Verifies all 9 criteria of the M1 Definition of Done:
 * - Nested loop break, continue, return, and exception unwinding
 * - Numeric tower correctness (bool as int subclass, BigInt exactness, float)
 * - Built-in type hierarchy (isinstance, issubclass)
 * - Sequential multiple-argument string formatting
 * - Extended slice normalization and float rejection
 */
public class Milestone1ConformanceTest {

    private Jython3Interpreter py;

    @BeforeEach
    void setUp() {
        py = Jython3Interpreter.trusted();
    }

    // ─── Criterion 3: Nested Control Flow & Stack Cleanup ───────────────────

    @Test
    void testNestedForBreakCleansStackAcrossMultipleLevels() {
        py.exec("""
            trace = []
            for i in range(4):
                for j in range(4):
                    if j == 2:
                        break
                    trace.append(i * 10 + j)
            """);
        assertEquals("[0, 1, 10, 11, 20, 21, 30, 31]", py.getGlobal("trace").toString());
    }

    @Test
    void testReturnInsideNestedForCleansStack() {
        py.exec("""
            def find_first_match():
                for i in range(5):
                    for j in range(5):
                        if i == 2 and j == 3:
                            return i * 100 + j
                return -1
            res = find_first_match()
            """);
        assertEquals("203", py.getGlobal("res").toString());
    }

    @Test
    void testExceptionUnwindingInsideNestedLoopRunsFinally() {
        py.exec("""
            cleanup = []
            try:
                for i in range(3):
                    try:
                        for j in range(3):
                            if i == 1 and j == 1:
                                raise ValueError("abort")
                    finally:
                        cleanup.append(i)
            except ValueError:
                pass
            """);
        // Finally must run for loop 0 (normal exit) and loop 1 (aborted exit)
        assertEquals("[0, 1]", py.getGlobal("cleanup").toString());
    }

    @Test
    void testBreakAcrossTryFinallyInLoop() {
        py.exec("""
            events = []
            for i in range(3):
                try:
                    if i == 1:
                        break
                    events.append("body_" + str(i))
                finally:
                    events.append("fin_" + str(i))
            """);
        assertEquals("['body_0', 'fin_0', 'fin_1']", py.getGlobal("events").toString());
    }

    // ─── Criterion 4: Numeric Tower Correctness ─────────────────────────────

    @Test
    void testBooleanIntegerTowerContracts() {
        py.exec("""
            # Value & type
            t_plus_t = True + True
            t_mul_5 = True * 5
            f_plus_1 = False + 1
            t_sub_t = True - True
            neg_t = -True
            t_div_2 = True / 2
            t_floordiv_2 = True // 2
            t_mod_2 = True % 2

            # Identities
            t_eq_1 = (True == 1)
            f_eq_0 = (False == 0)
            hash_eq = (hash(True) == hash(1))
            """);
        assertEquals("2", py.getGlobal("t_plus_t").toString());
        assertEquals("5", py.getGlobal("t_mul_5").toString());
        assertEquals("1", py.getGlobal("f_plus_1").toString());
        assertEquals("0", py.getGlobal("t_sub_t").toString());
        assertEquals("-1", py.getGlobal("neg_t").toString());
        assertEquals("0.5", py.getGlobal("t_div_2").toString());
        assertEquals("0", py.getGlobal("t_floordiv_2").toString());
        assertEquals("1", py.getGlobal("t_mod_2").toString());

        assertEquals("True", py.getGlobal("t_eq_1").toString());
        assertEquals("True", py.getGlobal("f_eq_0").toString());
        assertEquals("True", py.getGlobal("hash_eq").toString());
    }

    @Test
    void testBigIntFloorDivModuloInvariantAllSigns() {
        py.exec("""
            a = 10 ** 35
            b = 7
            inv_pp = (a == (a // b) * b + (a % b))
            inv_pn = (a == (a // -b) * -b + (a % -b))
            inv_np = (-a == (-a // b) * b + (-a % b))
            inv_nn = (-a == (-a // -b) * -b + (-a % -b))
            """);
        assertEquals("True", py.getGlobal("inv_pp").toString());
        assertEquals("True", py.getGlobal("inv_pn").toString());
        assertEquals("True", py.getGlobal("inv_np").toString());
        assertEquals("True", py.getGlobal("inv_nn").toString());
    }

    // ─── Criterion 5: Built-in Types & Subtyping ────────────────────────────

    @Test
    void testIsinstanceAndIssubclassContracts() {
        py.exec("""
            # isinstance checks
            i_int = isinstance(42, int)
            i_big = isinstance(10 ** 50, int)
            i_bool_is_int = isinstance(True, int)
            i_bool_is_bool = isinstance(True, bool)
            i_int_is_bool = isinstance(42, bool)
            i_str = isinstance("abc", str)
            i_float = isinstance(3.14, float)
            i_list = isinstance([1, 2], list)
            i_dict = isinstance({"k": "v"}, dict)

            # tuple of types
            i_tuple_hit = isinstance(42, (str, int))
            i_tuple_miss = isinstance(42, (str, list))

            # issubclass checks
            s_bool_int = issubclass(bool, int)
            s_int_bool = issubclass(int, bool)
            s_int_int = issubclass(int, int)
            s_tuple_target = issubclass(bool, (str, int))
            """);
        assertEquals("True", py.getGlobal("i_int").toString());
        assertEquals("True", py.getGlobal("i_big").toString());
        assertEquals("True", py.getGlobal("i_bool_is_int").toString());
        assertEquals("True", py.getGlobal("i_bool_is_bool").toString());
        assertEquals("False", py.getGlobal("i_int_is_bool").toString());
        assertEquals("True", py.getGlobal("i_str").toString());
        assertEquals("True", py.getGlobal("i_float").toString());
        assertEquals("True", py.getGlobal("i_list").toString());
        assertEquals("True", py.getGlobal("i_dict").toString());

        assertEquals("True", py.getGlobal("i_tuple_hit").toString());
        assertEquals("False", py.getGlobal("i_tuple_miss").toString());

        assertEquals("True", py.getGlobal("s_bool_int").toString());
        assertEquals("False", py.getGlobal("s_int_bool").toString());
        assertEquals("True", py.getGlobal("s_int_int").toString());
        assertEquals("True", py.getGlobal("s_tuple_target").toString());
    }

    // ─── Criterion 6: Formatting ────────────────────────────────────────────

    @Test
    void testSequentialStringFormattingContracts() {
        py.exec("""
            f1 = "%d + %d = %d" % (10, 20, 30)
            f2 = "%s has %d items (avg: %.2f)" % ("Basket", 3, 4.567)
            f3 = "%d%%" % 100
            f4 = "%d" % (10 ** 28)
            f5 = "%d" % True
            f6 = "%r" % "world"
            """);
        assertEquals("10 + 20 = 30", py.getGlobal("f1").toString());
        assertEquals("Basket has 3 items (avg: 4.57)", py.getGlobal("f2").toString());
        assertEquals("100%", py.getGlobal("f3").toString());
        assertEquals("10000000000000000000000000000", py.getGlobal("f4").toString());
        assertEquals("1", py.getGlobal("f5").toString());
        assertEquals("'world'", py.getGlobal("f6").toString());
    }

    @Test
    void testStringFormattingArgumentCountValidation() {
        assertThrows(InterpreterException.class, () -> py.eval("'%d %d' % 1"));
        assertThrows(InterpreterException.class, () -> py.eval("'%d' % (1, 2)"));
    }

    // ─── Slicing Invariants ─────────────────────────────────────────────────

    @Test
    void testSlicingInvariants() {
        py.exec("""
            seq = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
            big = 10 ** 100
            nbig = -big

            s_full = seq[:]
            s_rev = seq[::-1]
            s_step2 = seq[::2]
            s_big_start = seq[big:]
            s_nbig_start = seq[nbig:]
            s_big_stop = seq[:big]
            s_nbig_stop = seq[:nbig]
            """);
        assertEquals("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]", py.getGlobal("s_full").toString());
        assertEquals("[9, 8, 7, 6, 5, 4, 3, 2, 1, 0]", py.getGlobal("s_rev").toString());
        assertEquals("[0, 2, 4, 6, 8]", py.getGlobal("s_step2").toString());
        assertEquals("[]", py.getGlobal("s_big_start").toString());
        assertEquals("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]", py.getGlobal("s_nbig_start").toString());
        assertEquals("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]", py.getGlobal("s_big_stop").toString());
        assertEquals("[]", py.getGlobal("s_nbig_stop").toString());

        // Step zero must raise ValueError
        assertThrows(InterpreterException.class, () -> py.eval("[1, 2, 3][::0]"));
    }
}
