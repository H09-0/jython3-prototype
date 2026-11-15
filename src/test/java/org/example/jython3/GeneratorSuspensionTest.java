package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests true lazy generator frame suspension/resume, lifecycle transitions,
 * infinite generators, re-entrancy protection, cross-interpreter guard, and cleanup.
 */
class GeneratorSuspensionTest {

    @Test
    @DisplayName("Generators suspend and resume lazily step-by-step without eager evaluation")
    void lazySuspensionAndStepOrdering() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            events = []
            def g():
                for i in range(3):
                    events.append("before-" + str(i))
                    yield i
                    events.append("after-" + str(i))

            it = g()
            assert iter(it) is it
            assert events == []
            assert next(it) == 0
            assert events == ["before-0"]
            assert next(it) == 1
            assert events == ["before-0", "after-0", "before-1"]
            assert next(it) == 2
            assert events == ["before-0", "after-0", "before-1", "after-1", "before-2"]

            done = False
            try:
                next(it)
            except StopIteration:
                done = True
            assert done
            assert events == ["before-0", "after-0", "before-1", "after-1", "before-2", "after-2"]

            # Subsequent next() after completion stays StopIteration and never re-runs body
            again = False
            try:
                next(it)
            except StopIteration:
                again = True
            assert again
            assert len(events) == 6

            # Lazy generator expression does not compute items before next()
            expr_log = []
            def track(x):
                expr_log.append(x)
                return x * 10
            ge = (track(x) for x in [1, 2, 3])
            assert expr_log == []
            assert next(ge) == 10 and expr_log == [1]
            assert next(ge) == 20 and expr_log == [1, 2]
            assert list(ge) == [30] and expr_log == [1, 2, 3]

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("Infinite generators, early break + next(), all()/any() short-circuiting, and return value")
    void infiniteAndShortCircuitingGenerators() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def naturals(start=0):
                n = start
                while True:
                    yield n
                    n += 1

            inf = naturals(10)
            assert [next(inf), next(inf), next(inf)] == [10, 11, 12]

            # Mix for-loop break and subsequent next() without losing/skipping an element
            collected = []
            for x in inf:
                collected.append(x)
                if x == 14:
                    break
            assert collected == [13, 14]
            assert next(inf) == 15

            # all() and any() stop an infinite generator immediately
            seen = []
            def inf_check():
                i = 0
                while True:
                    seen.append(i)
                    yield i != 2
                    i += 1
            assert all(inf_check()) == False
            assert seen == [0, 1, 2]

            seen2 = []
            def inf_any():
                i = 0
                while True:
                    seen2.append(i)
                    yield i == 3
                    i += 1
            assert any(inf_any()) == True
            assert seen2 == [0, 1, 2, 3]

            # return value inside generator surfaces on StopIteration.value / args
            def ret_gen():
                yield 1
                return "final-val"
            rg = ret_gen()
            assert next(rg) == 1
            try:
                next(rg)
                ret_ok = False
            except StopIteration as si:
                ret_ok = (si.value == "final-val" and si.args == ("final-val",))
            assert ret_ok

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("Lifecycle states, re-entrant next(self) rejection, and cross-interpreter guard")
    void lifecycleReentrancyAndCrossInterpreter() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def simple():
                yield 1
            g = simple()
            """);
        var gen = (PyGenerator) interp.getGlobal("g");
        assertEquals(PyGenerator.State.CREATED, gen.state());
        interp.eval("v1 = next(g)");
        assertEquals("1", interp.getGlobal("v1").toString());
        assertEquals(PyGenerator.State.SUSPENDED, gen.state());
        interp.eval("try:\n    next(g)\nexcept StopIteration:\n    pass");
        assertEquals(PyGenerator.State.COMPLETED, gen.state());
        assertNull(gen.operandStack(), "completed generator must release operandStack");
        assertNull(gen.locals(), "completed generator must release locals");

        // Re-entrant next() on an already RUNNING generator raises ValueError
        var reentrant = assertThrows(InterpreterException.class, () -> interp.eval("""
            holder = [None]
            def self_call():
                yield next(holder[0])
            holder[0] = self_call()
            next(holder[0])
            """));
        assertEquals("ValueError", reentrant.typeName());
        assertTrue(reentrant.getMessage().contains("already executing"), reentrant.getMessage());

        // Cross-interpreter resume is rejected cleanly
        interp.eval("def fresh():\n    yield 42\ng_other = fresh()");
        var other = Jython3Interpreter.trusted();
        other.setGlobal("g_foreign", interp.getGlobal("g_other"));
        var cross = assertThrows(InterpreterException.class, () -> other.eval("next(g_foreign)"));
        assertEquals("RuntimeError", cross.typeName());
    }
}
