package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;

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

        attrs.put("sqrt",     fn("sqrt",     args -> new PyFloat(Math.sqrt(toDouble(args[0])))));
        attrs.put("sin",      fn("sin",      args -> new PyFloat(Math.sin(toDouble(args[0])))));
        attrs.put("cos",      fn("cos",      args -> new PyFloat(Math.cos(toDouble(args[0])))));
        attrs.put("tan",      fn("tan",      args -> new PyFloat(Math.tan(toDouble(args[0])))));
        attrs.put("atan2",    fn("atan2",    args -> new PyFloat(Math.atan2(toDouble(args[0]), toDouble(args[1])))));
        attrs.put("hypot",    fn("hypot",    args -> {
            double acc = 0.0;
            for (PyObject a : args) acc = Math.hypot(acc, toDouble(a));
            return new PyFloat(acc);
        }));
        attrs.put("degrees",  fn("degrees",  args -> new PyFloat(Math.toDegrees(toDouble(args[0])))));
        attrs.put("radians",  fn("radians",  args -> new PyFloat(Math.toRadians(toDouble(args[0])))));
        attrs.put("exp",      fn("exp",      args -> new PyFloat(Math.exp(toDouble(args[0])))));
        attrs.put("log",      fn("log",      args -> {
            double x = toDouble(args[0]);
            if (x <= 0.0) throw PyErrors.error("ValueError", "math domain error");
            if (args.length >= 2) {
                double base = toDouble(args[1]);
                if (base <= 0.0) throw PyErrors.error("ValueError", "math domain error");
                return new PyFloat(Math.log(x) / Math.log(base));
            }
            return new PyFloat(Math.log(x));
        }));
        attrs.put("log2",     fn("log2",     args -> {
            double x = toDouble(args[0]);
            if (x <= 0.0) throw PyErrors.error("ValueError", "math domain error");
            return new PyFloat(Math.log(x) / Math.log(2.0));
        }));
        attrs.put("log10",    fn("log10",    args -> {
            double x = toDouble(args[0]);
            if (x <= 0.0) throw PyErrors.error("ValueError", "math domain error");
            return new PyFloat(Math.log10(x));
        }));
        attrs.put("floor",    fn("floor",    args -> floorOrCeil(args[0], true)));
        attrs.put("ceil",     fn("ceil",     args -> floorOrCeil(args[0], false)));
        attrs.put("isnan",    fn("isnan",    args -> PyBool.of(Double.isNaN(toDouble(args[0])))));
        attrs.put("isinf",    fn("isinf",    args -> PyBool.of(Double.isInfinite(toDouble(args[0])))));
        attrs.put("isfinite", fn("isfinite", args -> PyBool.of(Double.isFinite(toDouble(args[0])))));
        attrs.put("abs",      fn("abs",      args -> {
            var a = args[0];
            if (a instanceof PyInt i) return PyInt.of(Math.abs(i.value()));
            if (a instanceof BigInt bi) return new BigInt(bi.value().abs());
            return new PyFloat(Math.abs(toDouble(a)));
        }));
        attrs.put("pow",      fn("pow",      args -> new PyFloat(Math.pow(toDouble(args[0]), toDouble(args[1])))));
        attrs.put("gcd",      fn("gcd",      args -> {
            BigInteger acc = BigInteger.ZERO;
            for (PyObject a : args) {
                acc = acc.gcd(toBigInt(a).abs());
            }
            return fromBigInt(acc);
        }));
        attrs.put("lcm",      fn("lcm",      args -> {
            if (args.length == 0) return PyInt.ONE;
            BigInteger acc = BigInteger.ONE;
            for (PyObject a : args) {
                BigInteger v = toBigInt(a).abs();
                if (v.signum() == 0) return PyInt.ZERO;
                acc = acc.divide(acc.gcd(v)).multiply(v);
            }
            return fromBigInt(acc);
        }));
        attrs.put("factorial", fn("factorial", args -> {
            PyOps.checkArgCount("factorial", args, 1);
            int n = PyOps.toInt(args[0]);
            if (n < 0) throw PyErrors.error("ValueError", "factorial() not defined for negative values");
            if (n > 10_000) throw PyErrors.error("ValueError", "factorial() argument exceeds limit (10000)");
            BigInteger acc = BigInteger.ONE;
            for (int i = 2; i <= n; i++) acc = acc.multiply(BigInteger.valueOf(i));
            return fromBigInt(acc);
        }));
        attrs.put("comb", fn("comb", args -> {
            PyOps.checkArgCount("comb", args, 2);
            int n = PyOps.toInt(args[0]);
            int k = PyOps.toInt(args[1]);
            if (n < 0 || k < 0) throw PyErrors.error("ValueError", "n and k must be non-negative integers");
            if (n > 10_000) throw PyErrors.error("ValueError", "comb() n exceeds limit (10000)");
            if (k > n) return PyInt.ZERO;
            k = Math.min(k, n - k);
            BigInteger res = BigInteger.ONE;
            for (int i = 1; i <= k; i++) {
                res = res.multiply(BigInteger.valueOf(n - k + i)).divide(BigInteger.valueOf(i));
            }
            return fromBigInt(res);
        }));
        attrs.put("perm", fn("perm", args -> {
            if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", "perm() takes 1 or 2 arguments");
            int n = PyOps.toInt(args[0]);
            int k = args.length == 2 ? PyOps.toInt(args[1]) : n;
            if (n < 0 || k < 0) throw PyErrors.error("ValueError", "n and k must be non-negative integers");
            if (n > 10_000) throw PyErrors.error("ValueError", "perm() n exceeds limit (10000)");
            if (k > n) return PyInt.ZERO;
            BigInteger res = BigInteger.ONE;
            for (int i = 0; i < k; i++) res = res.multiply(BigInteger.valueOf(n - i));
            return fromBigInt(res);
        }));

        attrs.put("pi",  new PyFloat(Math.PI));
        attrs.put("tau", new PyFloat(2.0 * Math.PI));
        attrs.put("e",   new PyFloat(Math.E));
        attrs.put("inf", new PyFloat(Double.POSITIVE_INFINITY));
        attrs.put("nan", new PyFloat(Double.NaN));

        return new PyModule("math", attrs);
    }

    private static BigInteger toBigInt(PyObject o) {
        if (o instanceof PyInt i) return BigInteger.valueOf(i.value());
        if (o instanceof BigInt bi) return bi.value();
        if (o instanceof PyBool b) return b.value() ? BigInteger.ONE : BigInteger.ZERO;
        throw PyErrors.error("TypeError", "'" + PyOps.typeName(o) + "' object cannot be interpreted as an integer");
    }

    private static PyObject fromBigInt(BigInteger bi) {
        if (bi.bitLength() < 32) return PyInt.of(bi.intValue());
        return new BigInt(bi);
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
