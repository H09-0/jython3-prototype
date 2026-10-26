package org.example.jython3.runtime;

import java.math.BigInteger;
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

    public static boolean isNumeric(PyObject o) {
        return o instanceof PyInt || o instanceof PyFloat || o instanceof PyBool || o instanceof BigInt;
    }

    public static double toDouble(PyObject o) {
        return switch (o) {
            case PyInt i -> i.value();
            case BigInt bi -> bi.value().doubleValue();
            case PyFloat f -> f.value();
            case PyBool b -> b.value() ? 1.0 : 0.0;
            case PyStr s -> {
                String str = s.value().trim();
                if (str.equalsIgnoreCase("nan") || str.equalsIgnoreCase("+nan") || str.equalsIgnoreCase("-nan")) {
                    yield Double.NaN;
                }
                if (str.equalsIgnoreCase("inf") || str.equalsIgnoreCase("+inf") || str.equalsIgnoreCase("infinity") || str.equalsIgnoreCase("+infinity")) {
                    yield Double.POSITIVE_INFINITY;
                }
                if (str.equalsIgnoreCase("-inf") || str.equalsIgnoreCase("-infinity")) {
                    yield Double.NEGATIVE_INFINITY;
                }
                try { yield Double.parseDouble(str); }
                catch (NumberFormatException e) { throw PyErrors.error("ValueError", "could not convert string to float: '" + s.value() + "'"); }
            }
            default -> throw PyErrors.error("TypeError", "expected number, got " + typeName(o));
        };
    }

    public static int toInt(PyObject o) {
        if (o instanceof PyInt i) return i.value();
        if (o instanceof PyBool b) return b.value() ? 1 : 0;
        if (o instanceof PyFloat f) return (int) f.value();
        throw PyErrors.error("TypeError", "expected int, got " + typeName(o));
    }

    public static boolean isTruthy(PyObject v) {
        return switch (v) {
            case PyBool b -> b.value();
            case PyInt i -> i.value() != 0;
            case BigInt bi -> !bi.value().equals(java.math.BigInteger.ZERO);
            case PyStr s -> !s.value().isEmpty();
            case PyNull n -> false;
            case PyFunction f -> true;
            case PyClass c -> true;
            case PyInstance inst -> true;
            case PyBoundMethod bm -> true;
            case PyList l -> !l.items().isEmpty();
            case PyDict d -> !d.map().isEmpty();
            case PyIterator it -> true;
            case PyFloat f -> f.value() != 0.0;
            case PyModule m -> true;
            case PyBuiltinFunction bf -> true;
            case PyException e -> true;
            case PyCompiledCodeWrapper cw -> true;
            case PyJavaObject jo -> jo.object() != null;
            case PyJavaClass jc -> true;
            case PyJavaMethod jm -> true;
            case PyJavaPackage jp -> true;
                case PyGenerator g -> true;
                case PySuper s -> true;
                case PySlice sl -> true;
        };
    }

    public static boolean pyEquals(PyObject a, PyObject b) {
        if (a instanceof PyNull && b instanceof PyNull) return true;
        if (a instanceof PyBool ab && b instanceof PyBool bb) return ab.value() == bb.value();
        if (a instanceof PyInt ai && b instanceof PyInt bi) return ai.value() == bi.value();
        if (a instanceof BigInt ba && b instanceof BigInt bb) return ba.value().equals(bb.value());
        if (a instanceof BigInt ba && b instanceof PyInt bi) return ba.value().equals(java.math.BigInteger.valueOf(bi.value()));
        if (a instanceof PyInt ai && b instanceof BigInt bb) return java.math.BigInteger.valueOf(ai.value()).equals(bb.value());
        if (a instanceof BigInt ba && b instanceof PyFloat bf) return equalsBigIntAndFloat(ba, bf);
        if (a instanceof PyFloat af && b instanceof BigInt bb) return equalsBigIntAndFloat(bb, af);
        if (a instanceof PyFloat af && b instanceof PyFloat bf) return af.value() == bf.value();
        if (a instanceof PyStr as2 && b instanceof PyStr bs) return as2.value().equals(bs.value());
        if (isNumeric(a) && isNumeric(b)) return toDouble(a) == toDouble(b);
        if (a instanceof PyList al && b instanceof PyList bl) {
            if (al.items().size() != bl.items().size()) return false;
            for (int i = 0; i < al.items().size(); i++) {
                if (!pyEquals(al.items().get(i), bl.items().get(i))) return false;
            }
            return true;
        }
        if (a instanceof PyDict ad && b instanceof PyDict bd) {
            return ad.map().equals(bd.map());
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
        if (isNumeric(a) && isNumeric(b)) return Double.compare(toDouble(a), toDouble(b));
        if (a instanceof PyStr as2 && b instanceof PyStr bs) return as2.value().compareTo(bs.value());
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

    public static boolean pyContains(PyObject container, PyObject item) {
        if (container instanceof PyList list) {
            for (var el : list.items()) if (pyEquals(el, item)) return true;
            return false;
        }
        if (container instanceof PyStr s && item instanceof PyStr sub) return s.value().contains(sub.value());
        if (container instanceof PyDict dict) return dict.map().containsKey(item);
        throw PyErrors.error("TypeError", "argument of type '" + typeName(container) + "' is not iterable");
    }

    public static String typeName(PyObject o) {
        return switch (o) {
            case PyInt i -> "int";
            case BigInt bi -> "int";
            case PyFloat f -> "float";
            case PyStr s -> "str";
            case PyBool b -> "bool";
            case PyNull n -> "NoneType";
            case PyList l -> "list";
            case PyDict d -> "dict";
            case PyFunction f -> "function";
            case PyClass c -> "type";
            case PyInstance inst -> inst.pyClass().name();
            case PyBoundMethod bm -> "method";
            case PyBuiltinFunction bf -> "builtin_function_or_method";
            case PyModule m -> "module";
            case PyIterator it -> "iterator";
            case PyException e -> e.typeName();
            case PyCompiledCodeWrapper cw -> "code";
            case PyJavaClass jc -> "JavaClass(" + jc.clazz().getSimpleName() + ")";
            case PyJavaObject jo -> "JavaObject(" + (jo.object() == null ? "null" : jo.object().getClass().getSimpleName()) + ")";
            case PyJavaMethod jm -> "JavaMethod(" + jm.name() + ")";
            case PyJavaPackage jp -> "JavaPackage(" + jp.packageName() + ")";
            case PyGenerator g -> "generator";
            case PySuper s -> "super";
            case PySlice sl -> "slice";
        };
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
        String result = fmt;
        if (args instanceof PyList list) {
            for (var item : list.items()) {
                result = result.replaceFirst("%s", item.toString());
                result = result.replaceFirst("%d", item.toString());
            }
        } else {
            result = result.replaceFirst("%s", args.toString());
            result = result.replaceFirst("%d", args.toString());
        }
        return result;
    }
}
