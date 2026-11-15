package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unifies Python type identity across primitives, collections, functions, builtins,
 * exceptions, user classes and instances.
 */
class TypeIdentityTest {

    @Test
    @DisplayName("type(), __name__, __class__, isinstance(), issubclass(), hasattr(), getattr()")
    void unifiedTypeIdentity() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class C(object):
                def m(self):
                    return 1
            class Sub(C):
                pass
            def f():
                pass

            c = C()
            s = Sub()
            checks = [
                type(1) is int,
                type(10 ** 30) is int,
                type(1.5) is float,
                type(True) is bool,
                type("x") is str,
                type([]) is list,
                type({}) is dict,
                type(()) is tuple,
                type(set()) is set,
                type(ValueError()) is ValueError,
                type(c) is C,
                type(s) is Sub,
                type(C) is type,
                type(ValueError) is type,
                type(int) is type,
                type(type) is type,
                type(C()).__name__ == "C",
                type(1).__name__ == "int",
                type("x").__name__ == "str",
                type([]).__name__ == "list",
                type({}).__name__ == "dict",
                type(set()).__name__ == "set",
                type(ValueError()).__name__ == "ValueError",
                type(f).__name__ == "function",
                type(lambda: 1) is type(f),
                type(len).__name__ == "builtin_function_or_method",
                type(c.m).__name__ == "method",
                type(None).__name__ == "NoneType",
                type(1)("42") == 42,
                (1).__class__ is int,
                "x".__class__ is str,
                [].__class__ is list,
                {}.__class__ is dict,
                set().__class__ is set,
                c.__class__ is C,
                f.__class__ is type(f),
                len.__class__ is type(len),
                int.__class__ is type,
                C.__class__ is type,
                isinstance(True, int),
                isinstance(1, type(1)),
                isinstance(c, object),
                isinstance(None, object),
                isinstance(C, type),
                isinstance(int, type),
                not isinstance(len, type),
                issubclass(bool, int),
                issubclass(type(True), int),
                issubclass(Sub, C),
                issubclass(C, object),
                issubclass(int, object),
                issubclass(ValueError, object),
                not issubclass(C, int),
                not issubclass(int, C),
                hasattr(int, "__name__"),
                hasattr("abc", "__class__"),
                getattr(type(1), "__name__") == "int",
                getattr("abc", "__class__") is str,
                str(type(1)) == "<class 'int'>",
                str(type("x")) == "<class 'str'>",
                str(type([])) == "<class 'list'>",
                str(type({})) == "<class 'dict'>",
                str(type(set())) == "<class 'set'>",
                str(type(ValueError())) == "<class 'ValueError'>",
            ]
            o = object()
            checks.append(type(o) is object and o.__class__ is object and isinstance(o, object))
            ok = False not in checks
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));

        interp.eval("res_cls = type(C())");
        assertInstanceOf(PyClass.class, interp.getGlobal("res_cls"));
        // Invalid type arguments raise TypeError
        for (String bad : new String[]{
                "x = isinstance(1, len)",
                "x = isinstance(1, 123)",
                "x = issubclass(1, int)",
                "x = issubclass(len, int)",
                "x = issubclass(int, len)",
                "x = issubclass(C, 123)",
        }) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(bad), bad);
            assertEquals("TypeError", e.typeName(), bad);
        }
    }
}
