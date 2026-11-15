package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;
import static org.example.jython3.stdlib.modules.StdlibSupport.toDouble;

/** Standard {@code math} module. */
public final class MathModule {
    private MathModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("sqrt",  fn("sqrt",  args -> new PyFloat(Math.sqrt(toDouble(args[0])))));
        attrs.put("sin",   fn("sin",   args -> new PyFloat(Math.sin(toDouble(args[0])))));
        attrs.put("cos",   fn("cos",   args -> new PyFloat(Math.cos(toDouble(args[0])))));
        attrs.put("tan",   fn("tan",   args -> new PyFloat(Math.tan(toDouble(args[0])))));
        attrs.put("log",   fn("log",   args -> new PyFloat(Math.log(toDouble(args[0])))));
        attrs.put("floor", fn("floor", args -> floorOrCeil(args[0], true)));
        attrs.put("ceil",  fn("ceil",  args -> floorOrCeil(args[0], false)));
        attrs.put("abs",   fn("abs",   args -> {
            var a = args[0];
            if (a instanceof PyInt i) return PyInt.of(Math.abs(i.value()));
            if (a instanceof BigInt bi) return new BigInt(bi.value().abs());
            return new PyFloat(Math.abs(toDouble(a)));
        }));
        attrs.put("pow",   fn("pow",   args -> new PyFloat(Math.pow(toDouble(args[0]), toDouble(args[1])))));

        attrs.put("pi",  new PyFloat(Math.PI));
        attrs.put("e",   new PyFloat(Math.E));
        attrs.put("inf", new PyFloat(Double.POSITIVE_INFINITY));

        return new PyModule("math", attrs);
    }

    private static PyObject floorOrCeil(PyObject arg, boolean isFloor) {
        if (arg instanceof PyInt || arg instanceof BigInt) return arg;
        if (arg instanceof PyBool b) return b.value() ? PyInt.ONE : PyInt.ZERO;
        double d = toDouble(arg);
        if (Double.isInfinite(d)) {
            throw PyErrors.error("OverflowError", "cannot convert float infinity to integer");
        }
        if (Double.isNaN(d)) {
            throw PyErrors.error("ValueError", "cannot convert float NaN to integer");
        }
        double rounded = isFloor ? Math.floor(d) : Math.ceil(d);
        if (rounded >= Integer.MIN_VALUE && rounded <= Integer.MAX_VALUE) {
            return PyInt.of((int) rounded);
        }
        BigInteger bi = new BigDecimal(rounded).toBigInteger();
        return new BigInt(bi);
    }
}
