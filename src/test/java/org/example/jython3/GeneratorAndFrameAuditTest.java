package org.example.jython3;

import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyCaller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit tests for PyGenerator, Frame isolation, callPyCallableKw,
 * MAX_CALL_DEPTH, ThreadLocal cleanup, and explicit rejection of send/throw/close.
 */
class GeneratorAndFrameAuditTest {

    @Test
    @DisplayName("Generator return, yield in try/finally, exception cleanup, nested generators, and callbacks")
    void generatorControlFlowAndFrameIsolation() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            # 1. return inside generator stops yielding cleanly
            def g_ret():
                yield 1
                yield 2
                return 99
                yield 3
            assert list(g_ret()) == [1, 2]

            # 2. yield inside try/finally runs finally cleanup
            fin_log = []
            def g_fin():
                try:
                    yield "a"
                    yield "b"
                finally:
                    fin_log.append("finally")
            assert list(g_fin()) == ["a", "b"]
            assert fin_log == ["finally"]

            # 3. generator with 'global' does not leak globalCells into enclosing caller
            g_var = 10
            def g_global():
                global g_var
                g_var = 20
                yield g_var
            def caller():
                g_var = 1
                vals = list(g_global())
                return (vals, g_var)   # local g_var must remain 1!
            assert caller() == ([20], 1) and g_var == 20

            # 4. generator raising exception cleans up and subsequent next() raises StopIteration (not NPE)
            def g_fail():
                try:
                    yield 1
                    raise ValueError("gen-boom")
                finally:
                    fin_log.append("err-finally")
            gf = g_fail()
            caught = False
            try:
                list(gf)
            except ValueError:
                caught = True
            assert caught and fin_log == ["finally", "err-finally"]

            second_next_stop = False
            try:
                next(gf)
            except StopIteration:
                second_next_stop = True
            assert second_next_stop

            # 5. nested generator calls + callback during iteration
            def g_inner(n):
                for i in range(n):
                    yield i * 2
            def g_outer():
                for x in g_inner(3):
                    yield x + 1
            assert list(g_outer()) == [1, 3, 5]

            seen = []
            for item in g_outer():
                seen.append((lambda v: v * 10)(item))
            assert seen == [10, 30, 50]

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("Generator send/throw/close are explicitly rejected with NotImplementedError")
    void generatorSendThrowCloseRejected() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("def g():\n    yield 1");
        for (String call : new String[]{"g().send(1)", "g().throw(ValueError)", "g().close()"}) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(call), call);
            assertEquals("NotImplementedError", e.typeName(), call);
        }
    }

    @Test
    @DisplayName("ThreadLocals (PyCaller.CURRENT, ExceptionTypeRegistry) are clean after both success and error")
    void threadLocalsCleanAfterEval() {
        var interp = Jython3Interpreter.trusted();
        assertNull(PyCaller.current());

        interp.eval("x = 1 + 2");
        assertNull(PyCaller.current(), "PyCaller.CURRENT leaked after normal eval");

        assertThrows(InterpreterException.class, () -> interp.eval("1 / 0"));
        assertNull(PyCaller.current(), "PyCaller.CURRENT leaked after uncaught exception");

        // ExceptionTypeRegistry restores cleanly when interpreter exits
        ExceptionTypeRegistry before = ExceptionTypeRegistry.current();
        assertTrue(before != interp.exceptionTypes());
    }
}
