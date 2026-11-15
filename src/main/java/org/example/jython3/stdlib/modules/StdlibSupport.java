package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;

import java.util.function.Function;

/** Shared helpers for Java-backed standard library modules. */
final class StdlibSupport {
    private StdlibSupport() {}

    static PyBuiltinFunction fn(String name, Function<PyObject[], PyObject> impl) {
        return new PyBuiltinFunction(name, impl);
    }

    static double toDouble(PyObject o) {
        return switch (o) {
            case PyInt i   -> i.value();
            case PyFloat f -> f.value();
            case PyBool b  -> b.value() ? 1.0 : 0.0;
            case BigInt bi -> {
                double d = bi.value().doubleValue();
                if (Double.isInfinite(d)) {
                    throw PyErrors.error("OverflowError", "int too large to convert to float");
                }
                yield d;
            }
            default -> throw PyErrors.error("TypeError",
                    "expected number, got " + PyOps.typeName(o));
        };
    }
}
