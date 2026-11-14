package org.example.jython3;

import org.example.jython3.compile.CompiledCode;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NonlocalAndBuiltinFixesTest {

    @Test
    @DisplayName("nonlocal allows inner function to mutate enclosing variable")
    void testBasicNonlocal() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def outer():
                x = 10
                def inner():
                    nonlocal x
                    x = 20
                inner()
                return x
            res = outer()
            """);
        assertEquals(new PyInt(20), interp.getGlobal("res"));
    }

    @Test
    @DisplayName("nonlocal across multiple enclosing scope levels")
    void testNestedNonlocal() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def f():
                x = 1
                def g():
                    def h():
                        nonlocal x
                        x = 99
                    h()
                g()
                return x
            res = f()
            """);
        assertEquals(new PyInt(99), interp.getGlobal("res"));
    }

    @Test
    @DisplayName("nonlocal persistent state across multiple calls (closure counter)")
    void testNonlocalClosureCounter() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def make_counter():
                count = 0
                def inc():
                    nonlocal count
                    count = count + 1
                    return count
                return inc

            c = make_counter()
            r1 = c()
            r2 = c()
            r3 = c()
            """);
        assertEquals(new PyInt(1), interp.getGlobal("r1"));
        assertEquals(new PyInt(2), interp.getGlobal("r2"));
        assertEquals(new PyInt(3), interp.getGlobal("r3"));
    }

    @Test
    @DisplayName("nonlocal without enclosing binding raises SyntaxError")
    void testNonlocalNoBinding() {
        var interp = Jython3Interpreter.trusted();
        InterpreterException ex = assertThrows(InterpreterException.class, () -> {
            interp.eval("""
                def f():
                    nonlocal unbound_var
                    unbound_var = 1
                f()
                """);
        });
        assertTrue(ex.getMessage().contains("no binding for nonlocal"), ex.getMessage());
    }

    @Test
    @DisplayName("int() with positional and keyword base raises TypeError for multiple values")
    void testIntDuplicateBase() {
        var interp = Jython3Interpreter.trusted();
        InterpreterException ex = assertThrows(InterpreterException.class, () -> {
            interp.eval("int('10', 16, base=10)");
        });
        assertTrue(ex.getMessage().contains("multiple values for argument 'base'"), ex.getMessage());
    }

    @Test
    @DisplayName("int() accepts base as keyword argument")
    void testIntKeywordBase() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("res = int('ff', base=16)");
        assertEquals(new PyInt(255), interp.getGlobal("res"));
    }

    @Test
    @DisplayName("print() with file parameter calling custom write method")
    void testPrintCustomFile() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Buffer:
                def __init__(self):
                    self.content = ""
                def write(self, s):
                    self.content = self.content + s

            b = Buffer()
            print("hello", "world", file=b, flush=True)
            res = b.content
            """);
        PyObject res = interp.getGlobal("res");
        assertNotNull(res);
        assertEquals("hello world\n", res.toString());
    }

    @Test
    @DisplayName("print() with invalid file raises AttributeError")
    void testPrintInvalidFile() {
        var interp = Jython3Interpreter.trusted();
        InterpreterException ex = assertThrows(InterpreterException.class, () -> {
            interp.eval("print('hello', file=42)");
        });
        assertTrue(ex.getMessage().contains("object has no attribute 'write'"), ex.getMessage());
    }

    @Test
    @DisplayName("evalCode and resetExecutionState execute precompiled code cleanly")
    void testEvalCodeAndReset() {
        var interp = Jython3Interpreter.trusted();
        CompiledCode code = interp.compileToCode("x = 42\ny = x * 2");
        interp.evalCode(code);
        assertEquals(new PyInt(42), interp.getGlobal("x"));
        assertEquals(new PyInt(84), interp.getGlobal("y"));

        interp.resetExecutionState();
        // After reset, previous globals are cleared
        assertEquals(null, interp.getGlobal("x"));
        assertEquals(null, interp.getGlobal("y"));

        // Re-execution works cleanly on same interpreter
        interp.evalCode(code);
        assertEquals(new PyInt(42), interp.getGlobal("x"));
        assertEquals(new PyInt(84), interp.getGlobal("y"));
    }

    @Test
    @DisplayName("PyInt.of caches small integers between -128 and 256")
    void testPyIntSmallIntegerCache() {
        org.junit.jupiter.api.Assertions.assertSame(PyInt.of(0), PyInt.of(0));
        org.junit.jupiter.api.Assertions.assertSame(PyInt.ZERO, PyInt.of(0));
        org.junit.jupiter.api.Assertions.assertSame(PyInt.ONE, PyInt.of(1));
        org.junit.jupiter.api.Assertions.assertSame(PyInt.of(-128), PyInt.of(-128));
        org.junit.jupiter.api.Assertions.assertSame(PyInt.of(256), PyInt.of(256));
        org.junit.jupiter.api.Assertions.assertNotSame(PyInt.of(1000), PyInt.of(1000));
        assertEquals(1000, PyInt.of(1000).value());
    }

    @Test
    @DisplayName("FastStack performs LIFO stack operations with dynamic growth")
    void testFastStackOperations() {
        var stack = new org.example.jython3.runtime.FastStack(2);
        assertTrue(stack.isEmpty());
        assertEquals(0, stack.size());

        stack.push(PyInt.of(10));
        stack.push(PyInt.of(20));
        stack.push(PyInt.of(30)); // triggers capacity growth
        assertEquals(3, stack.size());
        assertEquals(PyInt.of(30), stack.peek());
        assertEquals(PyInt.of(30), stack.pop());
        assertEquals(PyInt.of(20), stack.pop());
        assertEquals(PyInt.of(10), stack.pop());
        assertTrue(stack.isEmpty());
    }
}
