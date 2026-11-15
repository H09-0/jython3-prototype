package org.example.jython3.runtime;

import java.util.Objects;

/**
 * Built-in {@code @classmethod} descriptor.
 */
public record PyClassMethod(PyObject callable) implements PyObject {
    public PyClassMethod {
        Objects.requireNonNull(callable, "callable");
    }

    public PyBuiltinFunction bind(PyClass cls) {
        return new PyBuiltinFunction("classmethod", (args, kwargs) -> {
            PyObject[] boundArgs = new PyObject[args.length + 1];
            boundArgs[0] = cls;
            System.arraycopy(args, 0, boundArgs, 1, args.length);
            PyCaller caller = PyCaller.current();
            if (caller == null) {
                throw PyErrors.error("RuntimeError", "no active interpreter for classmethod");
            }
            if (callable instanceof PyBuiltinFunction bf) {
                return bf.callWithKw(boundArgs, kwargs);
            }
            if (!kwargs.isEmpty() && callable instanceof PyFunction fn) {
                return caller.callWithKw(fn, boundArgs, kwargs);
            }
            return caller.call(callable, boundArgs);
        });
    }

    @Override
    public String toString() {
        return "<classmethod(" + callable + ")>";
    }
}
