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
        if (o instanceof PyInt i) return i.value();
        if (o instanceof PyFloat f) return f.value();
        if (o instanceof PyBool b) return b.value() ? 1.0 : 0.0;
        if (o instanceof BigInt bi) {
            double d = bi.value().doubleValue();
            if (Double.isInfinite(d)) {
                throw PyErrors.error("OverflowError", "int too large to convert to float");
            }
            return d;
        }
        throw PyErrors.error("TypeError",
                "expected number, got " + PyOps.typeName(o));
    }
}
