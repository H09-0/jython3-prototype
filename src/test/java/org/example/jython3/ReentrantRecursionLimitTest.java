package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.security.SandboxedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Recursion through re-entrant interpreter paths (__init__, __enter__/__exit__,
 * class bodies, generators) must raise a catchable Python RecursionError and must
 * never surface a raw java.lang.StackOverflowError to the embedding application.
 */
class ReentrantRecursionLimitTest {

    private static final String RECURSIVE_INIT = """
            class R:
                def __init__(self, n):
                    if n > 0:
                        R(n - 1)
            """;

    @Test
    @DisplayName("Recursive __init__ raises a catchable RecursionError")
    void recursiveInitRaisesRecursionError() {
        var interp = Jython3Interpreter.trusted();
        interp.eval(RECURSIVE_INIT + """
            try:
                R(100000)
                outcome = "no-error"
            except RecursionError:
                outcome = "recursion-error"
            """);
        assertEquals("recursion-error", interp.getGlobal("outcome").toString());
    }

    @Test
    @DisplayName("Uncaught re-entrant recursion surfaces as InterpreterException(RecursionError)")
    void uncaughtRecursionIsInterpreterException() {
        var interp = Jython3Interpreter.trusted();
        var e = assertThrows(InterpreterException.class, () -> interp.eval(RECURSIVE_INIT + "R(100000)"));
        assertEquals("RecursionError", e.typeName());
    }

    @Test
    @DisplayName("Recursive __enter__ and recursive generators raise RecursionError")
    void recursiveEnterAndGeneratorsRaiseRecursionError() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class W:
                def __enter__(self):
                    with W():
                        pass
                def __exit__(self, t, v, tb):
                    return False
            results = []
            try:
                with W():
                    pass
            except RecursionError:
                results.append("enter")

            def g(n):
                yield n
                for v in g(n + 1):
                    yield v
            try:
                list(g(0))
            except RecursionError:
                results.append("gen")
            ok = results == ["enter", "gen"]
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("Interpreter remains usable after a RecursionError; moderate depth still allowed")
    void interpreterHealthyAfterRecursionError() {
        var interp = Jython3Interpreter.trusted();
        assertThrows(InterpreterException.class, () -> interp.eval(RECURSIVE_INIT + "R(100000)"));
        interp.eval("""
            class D:
                def __init__(self, n):
                    self.child = D(n - 1) if n > 0 else None
            d = D(200)
            def fact(n):
                return 1 if n <= 1 else n * fact(n - 1)
            healthy = fact(20) == 2432902008176640000 and d.child is not None
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("healthy"));
        // depth accounting is reset: the same deep-but-legal construction works repeatedly
        for (int i = 0; i < 5; i++) {
            interp.eval("d = D(200)");
        }
    }

    @Test
    @DisplayName("Small host thread stack: StackOverflowError is converted to RecursionError, never escapes")
    void smallHostStackNeverLeaksStackOverflowError() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                var interp = Jython3Interpreter.trusted();
                interp.eval(RECURSIVE_INIT + "R(100000)");
            } catch (Throwable ex) {
                thrown.set(ex);
            }
        }, "small-stack", 192 * 1024);
        t.start();
        t.join(60_000);
        Throwable ex = thrown.get();
        assertNotNull(ex, "deep recursion must fail");
        InterpreterException ie = org.junit.jupiter.api.Assertions.assertInstanceOf(
                InterpreterException.class, ex, "raw " + ex.getClass().getName() + " escaped");
        assertEquals("RecursionError", ie.typeName());
    }

    @Test
    @DisplayName("Sandboxed interpreter: re-entrant recursion is contained as RecursionError")
    void sandboxedRecursionIsContained() {
        var sandboxed = new Jython3Interpreter(SandboxedPolicy.get());
        var e = assertThrows(InterpreterException.class, () -> sandboxed.eval(RECURSIVE_INIT + "R(100000)"));
        assertEquals("RecursionError", e.typeName());
        sandboxed.eval("after = 1 + 1");
        assertEquals("2", sandboxed.getGlobal("after").toString());
    }

    @Test
    @DisplayName("Exception in a class body reaches the enclosing handler (class body has its own handler stack)")
    void classBodyExceptionReachesEnclosingHandler() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            log = []
            try:
                class C:
                    x = 1 / 0
                log.append("not-reached")
            except ZeroDivisionError:
                log.append("caught")

            def in_func():
                try:
                    class C2:
                        y = [][1]
                except IndexError:
                    return "caught-in-func"
                return "not-reached"
            log.append(in_func())

            class Ok:
                try:
                    z = 1 / 0
                except ZeroDivisionError:
                    z = "handled-in-body"
            log.append(Ok.z)
            ok = log == ["caught", "caught-in-func", "handled-in-body"]
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("log")));
        assertNull(interp.getGlobal("C"), "failed class definition must not bind the name");
    }
}
