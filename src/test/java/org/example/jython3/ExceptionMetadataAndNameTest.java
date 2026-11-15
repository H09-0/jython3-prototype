package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python-visible exception metadata (__context__, __cause__, __suppress_context__,
 * args, raise ... from ...), plus __name__ / __class__ / type() on classes,
 * exceptions, instances and callables.
 */
class ExceptionMetadataAndNameTest {

    @Test
    @DisplayName("Implicit __context__ is readable from Python across except and finally")
    void contextIsReadableFromPython() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            # 1. raise inside except records handled exception as __context__
            saved_orig = None
            try:
                try:
                    raise KeyError("k1")
                except KeyError as orig:
                    saved_orig = orig
                    raise ValueError("v1")
            except ValueError as outer:
                c1 = (
                    outer.__context__ is saved_orig,
                    type(outer.__context__) == KeyError,
                    outer.__context__.__class__.__name__,
                    str(outer.__context__),
                    outer.__cause__ is None,
                    outer.__suppress_context__,
                    outer.args,
                )

            # 2. raise inside finally while exception is pending records pending as __context__
            try:
                try:
                    raise KeyError("pending")
                finally:
                    raise RuntimeError("from-finally")
            except RuntimeError as r:
                c2 = (r.__context__.__class__.__name__, str(r.__context__), r.__cause__ is None)

            # 3. normal raise has __context__ is None and __cause__ is None
            try:
                raise ValueError("solo", 42)
            except ValueError as s:
                c3 = (s.__context__ is None, s.__cause__ is None, s.__suppress_context__, s.args)

            # 4. nested chaining depth 3
            try:
                try:
                    try:
                        raise KeyError("a")
                    except KeyError:
                        raise ValueError("b")
                except ValueError:
                    raise RuntimeError("c")
            except RuntimeError as e3:
                c4 = (
                    e3.__class__.__name__,
                    e3.__context__.__class__.__name__,
                    e3.__context__.__context__.__class__.__name__,
                    e3.__context__.__context__.__context__ is None,
                )

            ok = (
                c1 == (True, True, "KeyError", "k1", True, False, ("v1",))
                and c2 == ("KeyError", "pending", True)
                and c3 == (True, True, False, ("solo", 42))
                and c4 == ("RuntimeError", "ValueError", "KeyError", True)
            )
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("raise X from Y sets __cause__ and __suppress_context__; from None clears __cause__")
    void raiseFromSetsCauseAndSuppressesContext() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            saved_k = None
            try:
                try:
                    raise KeyError("low-level")
                except KeyError as k:
                    saved_k = k
                    raise ValueError("high-level") from k
            except ValueError as v:
                r1 = (
                    v.__cause__ is saved_k,
                    v.__context__ is saved_k,
                    v.__suppress_context__,
                    type(v.__cause__).__name__,
                )

            # raise X from Class instantiates Class
            try:
                raise RuntimeError("wrapped") from TypeError
            except RuntimeError as r:
                r2 = (r.__cause__.__class__.__name__, r.__suppress_context__, r.__context__ is None)

            # raise X from None suppresses context
            try:
                try:
                    raise KeyError("hidden")
                except KeyError:
                    raise ValueError("clean") from None
            except ValueError as v2:
                r3 = (
                    v2.__cause__ is None,
                    v2.__suppress_context__,
                    v2.__context__.__class__.__name__,
                )

            # non-exception cause raises TypeError
            try:
                raise ValueError("x") from 123
                r4 = "no-error"
            except TypeError:
                r4 = "TypeError"

            ok = (
                r1 == (True, True, True, "KeyError")
                and r2 == ("TypeError", True, True)
                and r3 == (True, True, "KeyError")
                and r4 == "TypeError"
            )
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("__name__, __class__, type(), hasattr() and getattr() on classes, exceptions, instances, functions")
    void dunderNameClassHasattrGetattr() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Base:
                def method(self):
                    return 1
            class Sub(Base):
                pass
            def my_fn():
                return 2

            inst = Sub()
            checks = [
                Base.__name__ == "Base",
                Sub.__name__ == "Sub",
                ValueError.__name__ == "ValueError",
                inst.__class__ is Sub,
                inst.__class__.__name__ == "Sub",
                type(inst) is Sub,
                type(inst).__name__ == "Sub",
                my_fn.__name__ == "my_fn",
                len.__name__ == "len",
                inst.method.__name__ == "method",
                hasattr(Sub, "__name__"),
                hasattr(inst, "__class__"),
                getattr(Sub, "__name__") == "Sub",
                getattr(inst, "__class__") is Sub,
                getattr(inst, "missing", "fallback") == "fallback",
            ]
            try:
                raise KeyError("x")
            except KeyError as e:
                checks.append(hasattr(e, "__context__"))
                checks.append(hasattr(e, "__cause__"))
                checks.append(getattr(e, "__class__") is KeyError)
                checks.append(type(e) is KeyError)
                checks.append(type(e).__name__ == "KeyError")
                checks.append(not hasattr(e, "nonexistent"))
                checks.append(getattr(e, "nonexistent", 99) == 99)

            ok = False not in checks
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));

        var ae = assertThrows(InterpreterException.class, () -> interp.eval("""
            try:
                raise ValueError("x")
            except ValueError as e:
                y = e.nonexistent
            """));
        assertEquals("AttributeError", ae.typeName());
    }

    @Test
    @DisplayName("Traceback captures nested functions, Java Bridge errors, and cleans frames on unwind")
    void tracebackNestedJavaBridgeAndCleanup() {
        var interp = Jython3Interpreter.trusted();
        var ie = assertThrows(InterpreterException.class, () -> interp.eval("""
            def level3():
                raise KeyError("deep")
            def level2():
                level3()
            def level1():
                level2()
            level1()
            """));
        String tb = interp.formatTraceback(ie, "<test>", """
            def level3():
                raise KeyError("deep")
            def level2():
                level3()
            def level1():
                level2()
            level1()
            """);
        assertTrue(tb.contains("in <module>"), tb);
        assertTrue(tb.contains("in level1"), tb);
        assertTrue(tb.contains("in level2"), tb);
        assertTrue(tb.contains("in level3"), tb);
        assertTrue(tb.contains("KeyError: deep"), tb);

        // Java Bridge exception unwinds cleanly and leaves interpreter/frames intact
        var jIe = assertThrows(InterpreterException.class, () -> interp.eval("""
            from java.util import ArrayList
            def j_call():
                lst = ArrayList()
                return lst.get(0)
            def wrapper():
                return j_call()
            wrapper()
            """));
        assertEquals("IndexError", jIe.typeName());
        String jTb = interp.formatTraceback(jIe, "<j>", "");
        assertTrue(jTb.contains("in wrapper") && jTb.contains("in j_call"), jTb);

        // Interpreter remains healthy and bare raise outside handler still errors
        interp.eval("x = 1 + 2");
        assertEquals("3", interp.getGlobal("x").toString());
        var bare = assertThrows(InterpreterException.class, () -> interp.eval("raise"));
        assertEquals("RuntimeError", bare.typeName());

        // Exception in finally replaces pending one, sets __context__, and clears active handler on unwind
        var finIe = assertThrows(InterpreterException.class, () -> interp.eval("""
            def f():
                try:
                    raise KeyError("orig")
                finally:
                    raise ValueError("override")
            f()
            """));
        assertEquals("ValueError", finIe.typeName());
        var pe = org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.example.jython3.runtime.PyException.class, finIe.value());
        org.junit.jupiter.api.Assertions.assertNotNull(pe.context());
        assertEquals("KeyError", pe.context().typeName());
    }
}
