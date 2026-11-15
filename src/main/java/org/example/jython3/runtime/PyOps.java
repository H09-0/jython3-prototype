package org.example.jython3.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared value operations used by the VM, the standard library, and the Java bridge. */
public final class PyOps {
    private PyOps() {}

    /**
     * Python-compatible floor division for arbitrary-precision integers.
     * Rounding is toward negative infinity for all sign combinations:
     *   floorDiv(10, 3) = 3
     *   floorDiv(-10, 3) = -4
     *   floorDiv(10, -3) = -4
     *   floorDiv(-10, -3) = 3
     */
    public static BigInteger floorDiv(BigInteger a, BigInteger b) {
        if (b.equals(BigInteger.ZERO)) {
            throw PyErrors.error("ZeroDivisionError", "integer division or modulo by zero");
        }
        BigInteger[] divRem = a.divideAndRemainder(b);
        BigInteger q = divRem[0];
        BigInteger r = divRem[1];
        if (!r.equals(BigInteger.ZERO) && (a.signum() ^ b.signum()) < 0) {
            q = q.subtract(BigInteger.ONE);
        }
        return q;
    }

    /**
     * Python-compatible modulo for arbitrary-precision integers.
     * Result has the same sign as the divisor b:
     *   floorMod(10, 3) = 1
     *   floorMod(-10, 3) = 2
     *   floorMod(10, -3) = -2
     *   floorMod(-10, -3) = -1
     * Preserves identity: a == (floorDiv(a, b) * b) + floorMod(a, b).
     */
    public static BigInteger floorMod(BigInteger a, BigInteger b) {
        if (b.equals(BigInteger.ZERO)) {
            throw PyErrors.error("ZeroDivisionError", "integer modulo by zero");
        }
        BigInteger r = a.remainder(b);
        if (!r.equals(BigInteger.ZERO) && (a.signum() ^ b.signum()) < 0) {
            r = r.add(b);
        }
        return r;
    }

    /**
     * Python 3 true division for integers: a / b.
     * Computes the quotient with exact precision and converts to float.
     * Raises ZeroDivisionError if b is zero.
     * Raises OverflowError if the quotient exceeds the IEEE 754 float range.
     */
    public static double trueDivideIntegers(BigInteger a, BigInteger b) {
        if (b.equals(BigInteger.ZERO)) {
            throw PyErrors.error("ZeroDivisionError", "division by zero");
        }
        if (a.equals(BigInteger.ZERO)) {
            return 0.0;
        }
        boolean negative = (a.signum() ^ b.signum()) < 0;
        BigInteger absA = a.abs();
        BigInteger absB = b.abs();

        int bitDiff = absA.bitLength() - absB.bitLength();
        if (bitDiff > 1024) {
            throw PyErrors.error("OverflowError", "integer division result too large for a float");
        }
        if (bitDiff < -1075) {
            return negative ? -0.0 : 0.0;
        }

        BigDecimal bda = new BigDecimal(absA);
        BigDecimal bdb = new BigDecimal(absB);
        BigDecimal quotient = bda.divide(bdb, MathContext.DECIMAL64);
        double res = quotient.doubleValue();
        if (Double.isInfinite(res)) {
            throw PyErrors.error("OverflowError", "integer division result too large for a float");
        }
        return negative ? -res : res;
    }

    public static boolean isNumeric(PyObject o) {
        return o instanceof PyInt || o instanceof PyFloat || o instanceof PyBool || o instanceof BigInt;
    }

    public static double toDouble(PyObject o) {
        if (o instanceof PyInt i) return i.value();
        if (o instanceof BigInt bi) {
            double d = bi.value().doubleValue();
            if (Double.isInfinite(d)) {
                throw PyErrors.error("OverflowError", "int too large to convert to float");
            }
            return d;
        }
        if (o instanceof PyFloat f) return f.value();
        if (o instanceof PyBool b) return b.value() ? 1.0 : 0.0;
        if (o instanceof PyStr s) {
            String str = s.value().trim();
            if (str.equalsIgnoreCase("nan") || str.equalsIgnoreCase("+nan") || str.equalsIgnoreCase("-nan")) {
                return Double.NaN;
            }
            if (str.equalsIgnoreCase("inf") || str.equalsIgnoreCase("+inf") || str.equalsIgnoreCase("infinity") || str.equalsIgnoreCase("+infinity")) {
                return Double.POSITIVE_INFINITY;
            }
            if (str.equalsIgnoreCase("-inf") || str.equalsIgnoreCase("-infinity")) {
                return Double.NEGATIVE_INFINITY;
            }
            try { return Double.parseDouble(str); }
            catch (NumberFormatException e) { throw PyErrors.error("ValueError", "could not convert string to float: '" + s.value() + "'"); }
        }
        throw PyErrors.error("TypeError", "expected number, got " + typeName(o));
    }

    public static int toInt(PyObject o) {
        if (o instanceof PyInt i) return i.value();
        if (o instanceof PyBool b) return b.value() ? 1 : 0;
        if (o instanceof PyFloat f) return (int) f.value();
        throw PyErrors.error("TypeError", "expected int, got " + typeName(o));
    }

    public static boolean isTruthy(PyObject v) {
        if (v instanceof PyRange r) return !r.isEmpty();
        if (v instanceof PyBytes b) return b.length() > 0;
        if (v instanceof PyByteArray ba) return ba.length() > 0;
        if (v instanceof PyBool b) return b.value();
        if (v instanceof PyInt i) return i.value() != 0;
        if (v instanceof BigInt bi) return !bi.value().equals(java.math.BigInteger.ZERO);
        if (v instanceof PyStr s) return !s.value().isEmpty();
        if (v instanceof PyNull) return false;
        if (v instanceof PyList l) return !l.items().isEmpty();
        if (v instanceof PyTuple t) return !t.items().isEmpty();
        if (v instanceof PySet s) return !s.isEmpty();
        if (v instanceof PyFrozenSet fs) return !fs.isEmpty();
        if (v instanceof PyDict d) return d.size() > 0;
        if (v instanceof PyFloat f) return f.value() != 0.0;
        if (v instanceof PyJavaObject jo) return jo.object() != null;
        if (v instanceof PyCell c) return c.isBound() && isTruthy(c.get());
        if (v instanceof PyInstance inst) {
            PyCaller caller = PyCaller.current();
            if (caller != null) {
                PyObject boolMember = inst.pyClass().lookupMember("__bool__");
                if (boolMember != null) {
                    PyObject res = caller.call(boolMember, new PyObject[]{inst});
                    if (res instanceof PyBool b) return b.value();
                    throw PyErrors.error("TypeError", "__bool__ should return bool, returned " + typeName(res));
                }
                PyObject lenMember = inst.pyClass().lookupMember("__len__");
                if (lenMember != null) {
                    PyObject res = caller.call(lenMember, new PyObject[]{inst});
                    int len = toInt(res);
                    if (len < 0) throw PyErrors.error("ValueError", "__len__() should return >= 0");
                    return len != 0;
                }
            }
            return true;
        }
        return v != null;
    }

    public static boolean pyEquals(PyObject a, PyObject b) {
        if (a instanceof PyNull && b instanceof PyNull) return true;
        if (a instanceof PyBool ab && b instanceof PyBool bb) return ab.value() == bb.value();
        if (a instanceof PyBool ab && b instanceof PyInt bi) return (ab.value() ? 1 : 0) == bi.value();
        if (a instanceof PyInt ai && b instanceof PyBool bb) return ai.value() == (bb.value() ? 1 : 0);
        if (a instanceof PyBool ab && b instanceof BigInt bb) {
            return bb.value().equals(ab.value() ? java.math.BigInteger.ONE : java.math.BigInteger.ZERO);
        }
        if (a instanceof BigInt ba && b instanceof PyBool bb) {
            return ba.value().equals(bb.value() ? java.math.BigInteger.ONE : java.math.BigInteger.ZERO);
        }
        if (a instanceof PyBool ab && b instanceof PyFloat bf) return (ab.value() ? 1.0 : 0.0) == bf.value();
        if (a instanceof PyFloat af && b instanceof PyBool bb) return af.value() == (bb.value() ? 1.0 : 0.0);
        if (a instanceof PyInt ai && b instanceof PyInt bi) return ai.value() == bi.value();
        if (a instanceof BigInt ba && b instanceof BigInt bb) return ba.value().equals(bb.value());
        if (a instanceof BigInt ba && b instanceof PyInt bi) return ba.value().equals(java.math.BigInteger.valueOf(bi.value()));
        if (a instanceof PyInt ai && b instanceof BigInt bb) return java.math.BigInteger.valueOf(ai.value()).equals(bb.value());
        if (a instanceof BigInt ba && b instanceof PyFloat bf) return equalsBigIntAndFloat(ba, bf);
        if (a instanceof PyFloat af && b instanceof BigInt bb) return equalsBigIntAndFloat(bb, af);
        if (a instanceof PyFloat af && b instanceof PyFloat bf) return af.value() == bf.value();
        if (a instanceof PyStr as2 && b instanceof PyStr bs) return as2.value().equals(bs.value());
        if (a instanceof PyBytes ab) return ab.equals(b);
        if (a instanceof PyByteArray aba) return aba.equals(b);
        if (a instanceof PyRange ra && b instanceof PyRange rb) return ra.equals(rb);
        if (isNumeric(a) && isNumeric(b)) return toDouble(a) == toDouble(b);
        if (a instanceof PyList al && b instanceof PyList bl) {
            if (al.items().size() != bl.items().size()) return false;
            for (int i = 0; i < al.items().size(); i++) {
                if (!pyEquals(al.items().get(i), bl.items().get(i))) return false;
            }
            return true;
        }
        if (a instanceof PyTuple ta && b instanceof PyTuple tb) {
            if (ta.items().size() != tb.items().size()) return false;
            for (int i = 0; i < ta.items().size(); i++) {
                if (!pyEquals(ta.items().get(i), tb.items().get(i))) return false;
            }
            return true;
        }
        if (a instanceof PyDict ad && b instanceof PyDict bd) {
            // Python semantics: keys matched via PyKey hash/eq (1 == 1.0 == True), values via pyEquals
            return ad.equals(bd);
        }
        if (a instanceof PySet sa) {
            return sa.equals(b);
        }
        if (a instanceof PyFrozenSet fa) {
            return fa.equals(b);
        }
        if (a instanceof PyBoundMethod ma && b instanceof PyBoundMethod mb) {
            return ma.instance() == mb.instance() && ma.fn() == mb.fn();
        }
        if (a instanceof PyInstance || b instanceof PyInstance) {
            PyCaller caller = PyCaller.current();
            if (caller != null) {
                if (a instanceof PyInstance ia) {
                    PyObject eq = ia.pyClass().lookupMember("__eq__");
                    if (eq != null) {
                        PyObject res = caller.call(eq, new PyObject[]{ia, b});
                        if (res != PyNotImplemented.INSTANCE) return isTruthy(res);
                    }
                }
                if (b instanceof PyInstance ib) {
                    PyObject eq = ib.pyClass().lookupMember("__eq__");
                    if (eq != null) {
                        PyObject res = caller.call(eq, new PyObject[]{ib, a});
                        if (res != PyNotImplemented.INSTANCE) return isTruthy(res);
                    }
                }
            }
            return a == b;
        }
        if (a instanceof PyClass || a instanceof PyException || a instanceof PyFunction
                || a instanceof PyBuiltinFunction
                || a instanceof PyModule
                || a instanceof PyHostHandle
                || a instanceof PyNotImplemented
                || a instanceof PyTraceback || a instanceof PyFrameInfo || a instanceof PyCodeInfo) {
            return a == b;
        }
        return false;
    }

    private static boolean equalsBigIntAndFloat(BigInt bi, PyFloat pf) {
        double d = pf.value();
        if (Double.isNaN(d) || Double.isInfinite(d)) return false;
        try {
            java.math.BigDecimal bdFromFloat = new java.math.BigDecimal(d);
            java.math.BigDecimal bdFromBigInt = new java.math.BigDecimal(bi.value());
            return bdFromBigInt.compareTo(bdFromFloat) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static int compareObjects(PyObject a, PyObject b) {
        if (a instanceof PyFloat af && Double.isNaN(af.value())) return -1;
        if (b instanceof PyFloat bf && Double.isNaN(bf.value())) return 1;
        if (a instanceof BigInt ba && b instanceof BigInt bb) {
            return ba.value().compareTo(bb.value());
        }
        if (a instanceof BigInt ba && b instanceof PyInt bi) {
            return ba.value().compareTo(java.math.BigInteger.valueOf(bi.value()));
        }
        if (a instanceof PyInt ai && b instanceof BigInt bb) {
            return java.math.BigInteger.valueOf(ai.value()).compareTo(bb.value());
        }
        if (a instanceof BigInt ba && b instanceof PyFloat bf) {
            return compareBigIntAndFloat(ba, bf);
        }
        if (a instanceof PyFloat af && b instanceof BigInt bb) {
            return -compareBigIntAndFloat(bb, af);
        }
        if (a instanceof PyTuple ta && b instanceof PyTuple tb) {
            int len = Math.min(ta.items().size(), tb.items().size());
            for (int i = 0; i < len; i++) {
                int c = compareObjects(ta.items().get(i), tb.items().get(i));
                if (c != 0) return c;
            }
            return Integer.compare(ta.items().size(), tb.items().size());
        }
        if (isNumeric(a) && isNumeric(b)) return Double.compare(toDouble(a), toDouble(b));
        if (a instanceof PyStr as2 && b instanceof PyStr bs) return as2.value().compareTo(bs.value());
        if (a instanceof PyInstance ia) {
            PyCaller caller = PyCaller.current();
            if (caller != null) {
                PyObject lt = ia.pyClass().lookupMember("__lt__");
                if (lt != null) {
                    PyObject res = caller.call(lt, new PyObject[]{ia, b});
                    if (res != PyNotImplemented.INSTANCE) {
                        if (isTruthy(res)) return -1;
                        PyObject gt = ia.pyClass().lookupMember("__gt__");
                        if (gt != null) {
                            PyObject gr = caller.call(gt, new PyObject[]{ia, b});
                            if (gr != PyNotImplemented.INSTANCE && isTruthy(gr)) return 1;
                        }
                        return pyEquals(ia, b) ? 0 : 1;
                    }
                }
            }
        }
        if (b instanceof PyInstance ib) {
            PyCaller caller = PyCaller.current();
            if (caller != null) {
                PyObject gt = ib.pyClass().lookupMember("__gt__");
                if (gt != null) {
                    PyObject res = caller.call(gt, new PyObject[]{ib, a});
                    if (res != PyNotImplemented.INSTANCE) {
                        return isTruthy(res) ? -1 : (pyEquals(a, ib) ? 0 : 1);
                    }
                }
            }
        }
        throw PyErrors.error("TypeError", "'<' not supported between instances of '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    private static int compareBigIntAndFloat(BigInt bi, PyFloat pf) {
        double d = pf.value();
        if (Double.isNaN(d)) return 1;
        if (d == Double.POSITIVE_INFINITY) return -1;
        if (d == Double.NEGATIVE_INFINITY) return 1;
        java.math.BigDecimal bdFloat = new java.math.BigDecimal(d);
        java.math.BigDecimal bdInt = new java.math.BigDecimal(bi.value());
        return bdInt.compareTo(bdFloat);
    }

    public static boolean isSetLike(PyObject o) {
        return o instanceof PySet || o instanceof PyFrozenSet;
    }

    public static int setSize(PyObject o) {
        if (o instanceof PySet s) return s.size();
        if (o instanceof PyFrozenSet fs) return fs.size();
        throw new IllegalArgumentException("not a set");
    }

    public static java.util.Set<PyKey> setKeys(PyObject o) {
        if (o instanceof PySet s) return s.keySet();
        if (o instanceof PyFrozenSet fs) return fs.keySet();
        throw new IllegalArgumentException("not a set");
    }

    public static boolean setIsSubset(PyObject sub, PyObject sup) {
        if (setSize(sub) > setSize(sup)) return false;
        var supKeys = setKeys(sup);
        for (PyKey k : setKeys(sub)) {
            if (!supKeys.contains(k)) return false;
        }
        return true;
    }

    public static boolean pyContains(PyObject container, PyObject item) {
        if (container instanceof PyList list) {
            for (var el : list.items()) if (pyEquals(el, item)) return true;
            return false;
        }
        if (container instanceof PyTuple tuple) {
            for (var el : tuple.items()) if (pyEquals(el, item)) return true;
            return false;
        }
        if (container instanceof PySet set) {
            return set.contains(item);
        }
        if (container instanceof PyFrozenSet fs) {
            return fs.contains(item);
        }
        if (container instanceof PyRange r) {
            return r.contains(item);
        }
        if (container instanceof PyBytes b) {
            return b.contains(item);
        }
        if (container instanceof PyByteArray ba) {
            return ba.contains(item);
        }
        if (container instanceof PyInstance inst) {
            PyObject containsFn = inst.pyClass().lookupMember("__contains__");
            if (containsFn != null) {
                PyCaller caller = PyCaller.current();
                if (caller != null) {
                    return isTruthy(caller.call(containsFn, new PyObject[]{inst, item}));
                }
            }
            PyObject iterFn = inst.pyClass().lookupMember("__iter__");
            if (iterFn != null) {
                PyCaller caller = PyCaller.current();
                if (caller != null) {
                    PyObject it = caller.call(iterFn, new PyObject[]{inst});
                    PyObject nextFn = it instanceof PyInstance ii ? ii.pyClass().lookupMember("__next__") : null;
                    if (it instanceof PyIterator pit) {
                        while (pit.hasNext()) if (pyEquals(pit.next(), item)) return true;
                        return false;
                    }
                    if (nextFn != null) {
                        while (true) {
                            try {
                                if (pyEquals(caller.call(nextFn, new PyObject[]{it}), item)) return true;
                            } catch (InterpreterException ie) {
                                if ("StopIteration".equals(ie.typeName())) return false;
                                throw ie;
                            }
                        }
                    }
                }
            }
        }
        if (container instanceof PyStr s && item instanceof PyStr sub) return s.value().contains(sub.value());
        if (container instanceof PyDict dict) return dict.containsKey(item);
        throw PyErrors.error("TypeError", "argument of type '" + typeName(container) + "' is not iterable");
    }

    public static String typeName(PyObject o) {
        if (o instanceof PyInt || o instanceof BigInt) return "int";
        if (o instanceof PyFloat) return "float";
        if (o instanceof PyStr) return "str";
        if (o instanceof PyBool) return "bool";
        if (o instanceof PyNull) return "NoneType";
        if (o instanceof PyList) return "list";
        if (o instanceof PyTuple) return "tuple";
        if (o instanceof PySet) return "set";
        if (o instanceof PyFrozenSet) return "frozenset";
        if (o instanceof PyDict) return "dict";
        if (o instanceof PyFunction) return "function";
        if (o instanceof PyClass) return "type";
        if (o instanceof PyInstance inst) return inst.pyClass().name();
        if (o instanceof PyBoundMethod) return "method";
        if (o instanceof PyBuiltinFunction bf) return bf.isType() ? "type" : "builtin_function_or_method";
        if (o instanceof PyModule) return "module";
        if (o instanceof PyIterator) return "iterator";
        if (o instanceof PyRange) return "range";
        if (o instanceof PyBytes) return "bytes";
        if (o instanceof PyByteArray) return "bytearray";
        if (o instanceof PyException e) return e.typeName();
        if (o instanceof PyCompiledCodeWrapper) return "code";
        if (o instanceof PyJavaClass jc) return "JavaClass(" + jc.clazz().getSimpleName() + ")";
        if (o instanceof PyJavaObject jo) return "JavaObject(" + (jo.object() == null ? "null" : jo.object().getClass().getSimpleName()) + ")";
        if (o instanceof PyJavaMethod jm) return "JavaMethod(" + jm.name() + ")";
        if (o instanceof PyJavaPackage jp) return "JavaPackage(" + jp.packageName() + ")";
        if (o instanceof PyGenerator) return "generator";
        if (o instanceof PySuper) return "super";
        if (o instanceof PySlice) return "slice";
        if (o instanceof PyCell c) return c.isBound() ? typeName(c.get()) : "cell";
        if (o instanceof PyNotImplemented) return "NotImplementedType";
        if (o instanceof PyTraceback) return "traceback";
        if (o instanceof PyFrameInfo) return "frame";
        if (o instanceof PyCodeInfo) return "code";
        if (o instanceof PyHostHandle hh) return hh.handleTypeName();
        if (o instanceof PyProperty) return "property";
        if (o instanceof PyStaticMethod) return "staticmethod";
        if (o instanceof PyClassMethod) return "classmethod";
        return o == null ? "NoneType" : o.getClass().getSimpleName();
    }

    public static void checkArgCount(String name, PyObject[] args, int expected) {
        if (args.length != expected) throw PyErrors.error("TypeError", name + "() takes exactly " + expected + " argument" + (expected != 1 ? "s" : "") + " (" + args.length + " given)");
    }

    public static List<PyObject> flattenArgs(String name, PyObject[] args) {
        if (args.length == 1 && args[0] instanceof PyList list) return new ArrayList<>(list.items());
        if (args.length > 1) { var items = new ArrayList<PyObject>(); Collections.addAll(items, args); return items; }
        throw PyErrors.error("TypeError", name + "() requires at least 1 argument");
    }

    public static String pyStringFormat(String fmt, PyObject args) {
        List<PyObject> argList;
        if (args instanceof PyTuple tuple) {
            argList = tuple.items();
        } else if (args instanceof PyList list) {
            argList = list.items();
        } else {
            argList = List.of(args);
        }

        var sb = new StringBuilder();
        int argIdx = 0;
        int n = fmt.length();
        int i = 0;

        while (i < n) {
            char ch = fmt.charAt(i);
            if (ch == '%') {
                if (i + 1 < n && fmt.charAt(i + 1) == '%') {
                    sb.append('%');
                    i += 2;
                    continue;
                }

                int specStart = i;
                i++; // skip '%'

                // Optional flags / width / precision: [0-9.+-]
                int widthDigitsStart = i;
                while (i < n && (Character.isDigit(fmt.charAt(i)) || fmt.charAt(i) == '.' || fmt.charAt(i) == '-' || fmt.charAt(i) == '+')) {
                    i++;
                }

                if (i >= n) {
                    throw PyErrors.error("ValueError", "incomplete format");
                }
                // Guard against %999999999f OOM allocation attacks
                String rawModifier = fmt.substring(widthDigitsStart, i);
                validatePercentFormatModifier(rawModifier);

                char conv = fmt.charAt(i);
                i++; // consume conversion char

                if (argIdx >= argList.size()) {
                    throw PyErrors.error("TypeError", "not enough arguments for format string");
                }

                PyObject arg = argList.get(argIdx++);
                String subSpec = fmt.substring(specStart, i);

                switch (conv) {
                    case 's' -> sb.append(arg instanceof PyStr s ? s.value() : arg.toString());
                    case 'r' -> {
                        if (arg instanceof PyStr s) sb.append("'").append(s.value()).append("'");
                        else sb.append(arg.toString());
                    }
                    case 'd', 'i' -> {
                        if (arg instanceof PyInt pi) sb.append(pi.value());
                        else if (arg instanceof PyBool pb) sb.append(pb.value() ? 1 : 0);
                        else if (arg instanceof BigInt bi) sb.append(bi.value().toString());
                        else if (arg instanceof PyFloat pf) sb.append((long) pf.value());
                        else throw PyErrors.error("TypeError", "%d format: a real number is required, not " + typeName(arg));
                    }
                    case 'f', 'F' -> {
                        double val = toDouble(arg);
                        sb.append(String.format(java.util.Locale.US, subSpec, val));
                    }
                    default -> sb.append(arg.toString());
                }
            } else {
                sb.append(ch);
                i++;
            }
        }

        if (argIdx < argList.size()) {
            throw PyErrors.error("TypeError", "not all arguments converted during string formatting");
        }

        return sb.toString();
    }

    private static void validatePercentFormatModifier(String raw) {
        if (raw.isEmpty()) return;
        int dot = raw.indexOf('.');
        String widthPart = (dot >= 0 ? raw.substring(0, dot) : raw).replace("+", "").replace("-", "");
        if (!widthPart.isEmpty()) {
            try {
                if (Integer.parseInt(widthPart) > 10_000) {
                    throw PyErrors.error("MemoryError", "format width exceeds safety limit (10000)");
                }
            } catch (NumberFormatException nfe) {
                throw PyErrors.error("MemoryError", "format width exceeds safety limit (10000)");
            }
        }
        if (dot >= 0) {
            String precPart = raw.substring(dot + 1);
            if (!precPart.isEmpty()) {
                try {
                    if (Integer.parseInt(precPart) > 1_000) {
                        throw PyErrors.error("ValueError", "format precision exceeds safety limit (1000)");
                    }
                } catch (NumberFormatException nfe) {
                    throw PyErrors.error("ValueError", "format precision exceeds safety limit (1000)");
                }
            }
        }
    }
}
