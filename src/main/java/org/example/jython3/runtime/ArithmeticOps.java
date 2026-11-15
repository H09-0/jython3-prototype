package org.example.jython3.runtime;

import org.example.jython3.security.ExecutionLimits;

import java.math.BigInteger;
import java.util.ArrayList;

import static org.example.jython3.runtime.PyOps.isNumeric;
import static org.example.jython3.runtime.PyOps.toDouble;
import static org.example.jython3.runtime.PyOps.typeName;

/**
 * Pure binary arithmetic helpers for the VM loop ({@code +}, {@code -}, {@code *}).
 * Extracted from {@code Jython3Interpreter} to keep VM dispatch concise.
 */
public final class ArithmeticOps {
    private ArithmeticOps() {}

    public static PyObject unwrapBool(PyObject o) {
        return (o instanceof PyBool b) ? (b.value() ? PyInt.ONE : PyInt.ZERO) : o;
    }

    public static PyObject add(PyObject rawL, PyObject rawR) {
        return add(rawL, rawR, null);
    }

    public static PyObject add(PyObject rawL, PyObject rawR, ExecutionLimits limits) {
        var r = unwrapBool(rawR);
        var l = unwrapBool(rawL);
        if (l instanceof PyStr ls && r instanceof PyStr rs) {
            if (limits != null && (long) ls.value().length() + rs.value().length() > limits.maxStringLength()) {
                throw PyErrors.error("MemoryError", "String length exceeds maximum allowed limit: " + limits.maxStringLength());
            }
            return new PyStr(ls.value() + rs.value());
        } else if (l instanceof PyList ll && r instanceof PyList rl) {
            if (limits != null && (long) ll.items().size() + rl.items().size() > limits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "List size exceeds maximum allowed limit: " + limits.maxCollectionSize());
            }
            var items = new ArrayList<>(ll.items());
            items.addAll(rl.items());
            return new PyList(items);
        } else if (l instanceof PyTuple lt && r instanceof PyTuple rt) {
            if (limits != null && (long) lt.items().size() + rt.items().size() > limits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "Tuple size exceeds maximum allowed limit: " + limits.maxCollectionSize());
            }
            var items = new ArrayList<>(lt.items());
            items.addAll(rt.items());
            return new PyTuple(items);
        } else if (l instanceof PyBytes lb && (r instanceof PyBytes || r instanceof PyByteArray)) {
            byte[] a = lb.bytesUnsafe();
            byte[] b = PyBytes.extractBuffer(r);
            if (limits != null && (long) a.length + b.length > limits.maxStringLength()) {
                throw PyErrors.error("MemoryError", "Bytes length exceeds maximum allowed limit: " + limits.maxStringLength());
            }
            byte[] out = new byte[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return PyBytes.wrapOwned(out);
        } else if (l instanceof PyByteArray lba && (r instanceof PyBytes || r instanceof PyByteArray)) {
            byte[] a = lba.copyBytes();
            byte[] b = PyBytes.extractBuffer(r);
            if (limits != null && (long) a.length + b.length > limits.maxStringLength()) {
                throw PyErrors.error("MemoryError", "ByteArray length exceeds maximum allowed limit: " + limits.maxStringLength());
            }
            byte[] out = new byte[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return new PyByteArray(out);
        } else if (l instanceof PyTuple && !(r instanceof PyTuple)) {
            throw PyErrors.error("TypeError", "can only concatenate tuple (not \"" + typeName(r) + "\") to tuple");
        } else if (!(l instanceof PyTuple) && r instanceof PyTuple) {
            throw PyErrors.error("TypeError", "can only concatenate " + typeName(l) + " (not \"tuple\") to " + typeName(l));
        } else if (l instanceof PyInt li && r instanceof PyInt ri) {
            long lv = li.value();
            long rv = ri.value();
            try {
                long res = Math.addExact(lv, rv);
                if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) {
                    return new BigInt(BigInteger.valueOf(res));
                }
                return PyInt.of((int) res);
            } catch (ArithmeticException ae) {
                return new BigInt(BigInteger.valueOf(lv).add(BigInteger.valueOf(rv)));
            }
        } else if (l instanceof BigInt bi && r instanceof BigInt bri) {
            return new BigInt(bi.value().add(bri.value()));
        } else if (l instanceof BigInt bi && r instanceof PyInt ri) {
            return new BigInt(bi.value().add(BigInteger.valueOf(ri.value())));
        } else if (l instanceof PyInt li && r instanceof BigInt bri) {
            return new BigInt(BigInteger.valueOf(li.value()).add(bri.value()));
        } else if (isNumeric(l) && isNumeric(r)) {
            return new PyFloat(toDouble(l) + toDouble(r));
        }
        PyObject dunder = tryBinaryDunder(rawL, rawR, "__add__", "__radd__");
        if (dunder != null) return dunder;
        throw PyErrors.error("TypeError", "unsupported operand types for +: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
    }

    public static PyObject tryBinaryDunder(PyObject l, PyObject r, String fwd, String rev) {
        if (!(l instanceof PyInstance) && !(r instanceof PyInstance)) return null;
        PyCaller caller = PyCaller.current();
        if (caller == null) return null;
        if (l instanceof PyInstance il) {
            PyObject fn = il.pyClass().lookupMember(fwd);
            if (fn != null) {
                PyObject res = caller.call(fn, new PyObject[]{il, r});
                if (res != PyNotImplemented.INSTANCE) return res;
            }
        }
        if (r instanceof PyInstance ir && (l != r || !fwd.equals(rev))) {
            PyObject fn = ir.pyClass().lookupMember(rev);
            if (fn != null) {
                PyObject res = caller.call(fn, new PyObject[]{ir, l});
                if (res != PyNotImplemented.INSTANCE) return res;
            }
        }
        return null;
    }

    public static PyObject subtract(PyObject rawL, PyObject rawR) {
        var r = unwrapBool(rawR);
        var l = unwrapBool(rawL);
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            long lv = li.value();
            long rv = ri.value();
            try {
                long res = Math.subtractExact(lv, rv);
                if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) {
                    return new BigInt(BigInteger.valueOf(res));
                }
                return PyInt.of((int) res);
            } catch (ArithmeticException ae) {
                return new BigInt(BigInteger.valueOf(lv).subtract(BigInteger.valueOf(rv)));
            }
        } else if (l instanceof BigInt bi && r instanceof BigInt bri) {
            return new BigInt(bi.value().subtract(bri.value()));
        } else if (l instanceof BigInt bi && r instanceof PyInt ri) {
            return new BigInt(bi.value().subtract(BigInteger.valueOf(ri.value())));
        } else if (l instanceof PyInt li && r instanceof BigInt bri) {
            return new BigInt(BigInteger.valueOf(li.value()).subtract(bri.value()));
        } else if (isNumeric(l) && isNumeric(r)) {
            return new PyFloat(toDouble(l) - toDouble(r));
        }
        if (PyOps.isSetLike(rawL) && PyOps.isSetLike(rawR)) {
            return setDifference(rawL, rawR);
        }
        PyObject dunder = tryBinaryDunder(rawL, rawR, "__sub__", "__rsub__");
        if (dunder != null) return dunder;
        throw PyErrors.error("TypeError",
                "unsupported operand type(s) for -: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
    }

    public static PyObject inplaceSubtract(PyObject l, PyObject r) {
        if (l instanceof PySet s) {
            if (!PyOps.isSetLike(r)) {
                throw PyErrors.error("TypeError",
                        "unsupported operand type(s) for -=: 'set' and '" + typeName(r) + "'");
            }
            if (s == r) {
                s.clear();
            } else {
                s.removeAllKeys(PyOps.setKeys(r));
            }
            return s;
        }
        return subtract(l, r);
    }

    public static PyObject inplaceBitwiseOr(PyObject l, PyObject r) {
        if (l instanceof PySet s) {
            if (!PyOps.isSetLike(r)) {
                throw PyErrors.error("TypeError",
                        "unsupported operand type(s) for |=: 'set' and '" + typeName(r) + "'");
            }
            if (s != r) {
                for (PyObject v : setItems(r)) s.add(v);
            }
            return s;
        }
        return bitwiseOr(l, r);
    }

    public static PyObject inplaceBitwiseAnd(PyObject l, PyObject r) {
        if (l instanceof PySet s) {
            if (!PyOps.isSetLike(r)) {
                throw PyErrors.error("TypeError",
                        "unsupported operand type(s) for &=: 'set' and '" + typeName(r) + "'");
            }
            if (s != r) {
                s.retainAllKeys(PyOps.setKeys(r));
            }
            return s;
        }
        return bitwiseAnd(l, r);
    }

    public static PyObject inplaceBitwiseXor(PyObject l, PyObject r) {
        if (l instanceof PySet s) {
            if (!PyOps.isSetLike(r)) {
                throw PyErrors.error("TypeError",
                        "unsupported operand type(s) for ^=: 'set' and '" + typeName(r) + "'");
            }
            if (s == r) {
                s.clear();
            } else {
                var snapshot = new ArrayList<PyObject>(setItems(r));
                for (PyObject v : snapshot) {
                    if (!s.remove(v)) {
                        s.add(v);
                    }
                }
            }
            return s;
        }
        return bitwiseXor(l, r);
    }

    public static PyObject inplaceMultiply(PyObject l, PyObject r, ExecutionLimits limits) {
        if (l instanceof PyList list) {
            PyObject nObj = unwrapBool(r);
            int count;
            if (nObj instanceof PyInt pi) {
                count = pi.value();
            } else if (nObj instanceof BigInt bi) {
                if (bi.value().signum() <= 0) {
                    list.items().clear();
                    return list;
                }
                if (list.items().isEmpty()) return list;
                try {
                    count = bi.value().intValueExact();
                } catch (ArithmeticException ae) {
                    throw PyErrors.error("OverflowError",
                            "List size exceeds maximum allowed limit: " + limits.maxCollectionSize());
                }
            } else {
                throw PyErrors.error("TypeError",
                        "can't multiply sequence by non-int of type '" + typeName(r) + "'");
            }
            if (count <= 0) {
                list.items().clear();
                return list;
            }
            int size = list.items().size();
            long targetLen = (long) size * count;
            if (targetLen > limits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError",
                        "List size exceeds maximum allowed limit: " + limits.maxCollectionSize());
            }
            var orig = new ArrayList<PyObject>(list.items());
            for (int c = 1; c < count; c++) {
                list.items().addAll(orig);
            }
            return list;
        }
        return multiply(l, r, limits);
    }

    private static PyObject buildSetResult(boolean mutable, java.util.Map<PyKey, PyObject> entries) {
        if (mutable) {
            var res = new PySet();
            for (PyObject v : entries.values()) res.add(v);
            return res;
        }
        return PyFrozenSet.fromValidatedMap(entries);
    }

    private static java.util.Collection<PyObject> setItems(PyObject o) {
        return (o instanceof PySet s) ? s.items() : ((PyFrozenSet) o).items();
    }

    private static boolean setHasKey(PyObject o, PyKey k) {
        return (o instanceof PySet s) ? s.containsKey(k) : ((PyFrozenSet) o).containsKey(k);
    }

    private static PyObject setUnion(PyObject l, PyObject r) {
        var map = new java.util.LinkedHashMap<PyKey, PyObject>();
        for (PyObject v : setItems(l)) map.putIfAbsent(new PyKey(v), v);
        for (PyObject v : setItems(r)) map.putIfAbsent(new PyKey(v), v);
        return buildSetResult(l instanceof PySet, map);
    }

    private static PyObject setIntersection(PyObject l, PyObject r) {
        var map = new java.util.LinkedHashMap<PyKey, PyObject>();
        for (PyObject v : setItems(l)) {
            PyKey k = new PyKey(v);
            if (setHasKey(r, k)) map.putIfAbsent(k, v);
        }
        return buildSetResult(l instanceof PySet, map);
    }

    private static PyObject setDifference(PyObject l, PyObject r) {
        var map = new java.util.LinkedHashMap<PyKey, PyObject>();
        for (PyObject v : setItems(l)) {
            PyKey k = new PyKey(v);
            if (!setHasKey(r, k)) map.putIfAbsent(k, v);
        }
        return buildSetResult(l instanceof PySet, map);
    }

    private static PyObject setSymmetricDifference(PyObject l, PyObject r) {
        var map = new java.util.LinkedHashMap<PyKey, PyObject>();
        for (PyObject v : setItems(l)) {
            PyKey k = new PyKey(v);
            if (!setHasKey(r, k)) map.putIfAbsent(k, v);
        }
        for (PyObject v : setItems(r)) {
            PyKey k = new PyKey(v);
            if (!setHasKey(l, k)) map.putIfAbsent(k, v);
        }
        return buildSetResult(l instanceof PySet, map);
    }

    public static PyObject multiply(PyObject rawL, PyObject rawR, ExecutionLimits limits) {
        var r = unwrapBool(rawR);
        var l = unwrapBool(rawL);
        if (l instanceof PyStr s && r instanceof PyInt n) {
            long newLen = (long) s.value().length() * Math.max(0, n.value());
            if (newLen > limits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + limits.maxStringLength());
            }
            return new PyStr(s.value().repeat(Math.max(0, n.value())));
        } else if (l instanceof PyInt n && r instanceof PyStr s) {
            long newLen = (long) s.value().length() * Math.max(0, n.value());
            if (newLen > limits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + limits.maxStringLength());
            }
            return new PyStr(s.value().repeat(Math.max(0, n.value())));
        } else if (l instanceof PyStr s && r instanceof BigInt n) {
            long newLen = (long) s.value().length() * Math.max(0, n.value().longValue());
            if (newLen > limits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + limits.maxStringLength());
            }
            return new PyStr(s.value().repeat(Math.max(0, n.value().intValue())));
        } else if (l instanceof BigInt n && r instanceof PyStr s) {
            long newLen = (long) s.value().length() * Math.max(0, n.value().longValue());
            if (newLen > limits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + limits.maxStringLength());
            }
            return new PyStr(s.value().repeat(Math.max(0, n.value().intValue())));
        } else if (l instanceof PyList ll && r instanceof PyInt n) {
            long newSize = (long) ll.items().size() * Math.max(0, n.value());
            if (newSize > limits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "List size exceeds maximum allowed limit: " + limits.maxCollectionSize());
            }
            var items = new ArrayList<PyObject>();
            long count = Math.max(0, n.value());
            for (long i = 0; i < count; i++) items.addAll(ll.items());
            return new PyList(items);
        } else if (l instanceof PyTuple tt && r instanceof PyInt n) {
            long count = Math.max(0, n.value());
            long newSize = (long) tt.items().size() * count;
            if (newSize > limits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "Tuple size exceeds maximum allowed limit: " + limits.maxCollectionSize());
            }
            var items = new ArrayList<PyObject>();
            for (long i = 0; i < count; i++) items.addAll(tt.items());
            return new PyTuple(items);
        } else if (l instanceof PyInt n && r instanceof PyTuple tt) {
            long count = Math.max(0, n.value());
            long newSize = (long) tt.items().size() * count;
            if (newSize > limits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "Tuple size exceeds maximum allowed limit: " + limits.maxCollectionSize());
            }
            var items = new ArrayList<PyObject>();
            for (long i = 0; i < count; i++) items.addAll(tt.items());
            return new PyTuple(items);
        } else if (l instanceof PyBytes pb && (r instanceof PyInt || r instanceof BigInt)) {
            return repeatBytes(pb.bytesUnsafe(), r, limits, true);
        } else if ((l instanceof PyInt || l instanceof BigInt) && r instanceof PyBytes pb) {
            return repeatBytes(pb.bytesUnsafe(), l, limits, true);
        } else if (l instanceof PyByteArray pba && (r instanceof PyInt || r instanceof BigInt)) {
            return repeatBytes(pba.copyBytes(), r, limits, false);
        } else if ((l instanceof PyInt || l instanceof BigInt) && r instanceof PyByteArray pba) {
            return repeatBytes(pba.copyBytes(), l, limits, false);
        } else if (l instanceof PyInt li && r instanceof PyInt ri) {
            long lv = li.value();
            long rv = ri.value();
            try {
                long res = Math.multiplyExact(lv, rv);
                if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) {
                    return new BigInt(BigInteger.valueOf(res));
                }
                return PyInt.of((int) res);
            } catch (ArithmeticException ae) {
                return new BigInt(BigInteger.valueOf(lv).multiply(BigInteger.valueOf(rv)));
            }
        } else if (l instanceof BigInt bi && r instanceof BigInt bri) {
            return new BigInt(bi.value().multiply(bri.value()));
        } else if (l instanceof BigInt bi && r instanceof PyInt ri) {
            return new BigInt(bi.value().multiply(BigInteger.valueOf(ri.value())));
        } else if (l instanceof PyInt li && r instanceof BigInt bri) {
            return new BigInt(BigInteger.valueOf(li.value()).multiply(bri.value()));
        } else if (isNumeric(l) && isNumeric(r)) {
            return new PyFloat(toDouble(l) * toDouble(r));
        }
        PyObject dunder = tryBinaryDunder(rawL, rawR, "__mul__", "__rmul__");
        if (dunder != null) return dunder;
        throw PyErrors.error("TypeError", "unsupported operand types for *: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
    }

    public static PyObject bitwiseAnd(PyObject rawL, PyObject rawR) {
        if (PyOps.isSetLike(rawL) && PyOps.isSetLike(rawR)) {
            return setIntersection(rawL, rawR);
        }
        if (rawL instanceof PyBool lb && rawR instanceof PyBool rb) {
            return PyBool.of(lb.value() & rb.value());
        }
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            return PyInt.of(li.value() & ri.value());
        }
        if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
            BigInteger ba = l instanceof PyInt li ? BigInteger.valueOf(li.value()) : ((BigInt) l).value();
            BigInteger bb = r instanceof PyInt ri ? BigInteger.valueOf(ri.value()) : ((BigInt) r).value();
            return fromBigInteger(ba.and(bb));
        }
        throw PyErrors.error("TypeError",
                "unsupported operand type(s) for &: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
    }

    public static PyObject bitwiseOr(PyObject rawL, PyObject rawR) {
        if (PyOps.isSetLike(rawL) && PyOps.isSetLike(rawR)) {
            return setUnion(rawL, rawR);
        }
        if (rawL instanceof PyBool lb && rawR instanceof PyBool rb) {
            return PyBool.of(lb.value() | rb.value());
        }
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            return PyInt.of(li.value() | ri.value());
        }
        if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
            BigInteger ba = l instanceof PyInt li ? BigInteger.valueOf(li.value()) : ((BigInt) l).value();
            BigInteger bb = r instanceof PyInt ri ? BigInteger.valueOf(ri.value()) : ((BigInt) r).value();
            return fromBigInteger(ba.or(bb));
        }
        throw PyErrors.error("TypeError",
                "unsupported operand type(s) for |: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
    }

    public static PyObject bitwiseXor(PyObject rawL, PyObject rawR) {
        if (PyOps.isSetLike(rawL) && PyOps.isSetLike(rawR)) {
            return setSymmetricDifference(rawL, rawR);
        }
        if (rawL instanceof PyBool lb && rawR instanceof PyBool rb) {
            return PyBool.of(lb.value() ^ rb.value());
        }
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            return PyInt.of(li.value() ^ ri.value());
        }
        if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
            BigInteger ba = l instanceof PyInt li ? BigInteger.valueOf(li.value()) : ((BigInt) l).value();
            BigInteger bb = r instanceof PyInt ri ? BigInteger.valueOf(ri.value()) : ((BigInt) r).value();
            return fromBigInteger(ba.xor(bb));
        }
        throw PyErrors.error("TypeError",
                "unsupported operand type(s) for ^: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
    }

    public static PyObject bitwiseInvert(PyObject rawV) {
        var v = unwrapBool(rawV);
        if (v instanceof PyInt i) {
            return PyInt.of(~i.value());
        }
        if (v instanceof BigInt bi) {
            return fromBigInteger(bi.value().not());
        }
        throw PyErrors.error("TypeError", "bad operand type for unary ~: '" + typeName(rawV) + "'");
    }

    public static PyObject leftShift(PyObject rawL, PyObject rawR, ExecutionLimits limits) {
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (!((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt))) {
            throw PyErrors.error("TypeError",
                    "unsupported operand type(s) for <<: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
        }
        int shift = extractShiftCount(r, limits);
        if (shift == 0) return l;
        if (l instanceof PyInt li && shift < 31) {
            long res = ((long) li.value()) << shift;
            if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) {
                return PyInt.of((int) res);
            }
        }
        BigInteger base = l instanceof PyInt li ? BigInteger.valueOf(li.value()) : ((BigInt) l).value();
        return fromBigInteger(base.shiftLeft(shift));
    }

    public static PyObject rightShift(PyObject rawL, PyObject rawR) {
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (!((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt))) {
            throw PyErrors.error("TypeError",
                    "unsupported operand type(s) for >>: '" + typeName(rawL) + "' and '" + typeName(rawR) + "'");
        }
        if (r instanceof PyInt ri) {
            int shift = ri.value();
            if (shift < 0) throw PyErrors.error("ValueError", "negative shift count");
            if (shift == 0) return l;
            if (l instanceof PyInt li) {
                if (shift >= 32) return li.value() < 0 ? PyInt.MINUS_ONE : PyInt.ZERO;
                return PyInt.of(li.value() >> shift);
            }
            BigInteger base = ((BigInt) l).value();
            return fromBigInteger(base.shiftRight(shift));
        }
        BigInteger shiftBi = ((BigInt) r).value();
        if (shiftBi.signum() < 0) throw PyErrors.error("ValueError", "negative shift count");
        BigInteger base = l instanceof PyInt li ? BigInteger.valueOf(li.value()) : ((BigInt) l).value();
        if (shiftBi.compareTo(BigInteger.valueOf(base.bitLength() + 1L)) >= 0) {
            return base.signum() < 0 ? PyInt.MINUS_ONE : PyInt.ZERO;
        }
        return fromBigInteger(base.shiftRight(shiftBi.intValue()));
    }

    private static int extractShiftCount(PyObject r, ExecutionLimits limits) {
        BigInteger shiftBi = r instanceof PyInt ri ? BigInteger.valueOf(ri.value()) : ((BigInt) r).value();
        if (shiftBi.signum() < 0) {
            throw PyErrors.error("ValueError", "negative shift count");
        }
        if (shiftBi.compareTo(BigInteger.valueOf(limits.maxExponent())) > 0) {
            throw PyErrors.error("OverflowError", "shift count exceeds maximum allowed limit: " + limits.maxExponent());
        }
        return shiftBi.intValueExact();
    }

    private static PyObject fromBigInteger(BigInteger val) {
        if (val.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
            val.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
            return PyInt.of(val.intValue());
        }
        return new BigInt(val);
    }

    public static PyObject floorDiv(PyObject rawL, PyObject rawR) {
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            if (ri.value() == 0) throw PyErrors.error("ZeroDivisionError", "integer division or modulo by zero");
            if (li.value() == Integer.MIN_VALUE && ri.value() == -1) {
                return new BigInt(BigInteger.valueOf(Integer.MIN_VALUE).negate());
            }
            return PyInt.of(Math.floorDiv(li.value(), ri.value()));
        }
        if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
            BigInteger ba = l instanceof PyInt pi ? BigInteger.valueOf(pi.value()) : ((BigInt) l).value();
            BigInteger bb = r instanceof PyInt pi ? BigInteger.valueOf(pi.value()) : ((BigInt) r).value();
            return fromBigInteger(PyOps.floorDiv(ba, bb));
        }
        if (isNumeric(l) && isNumeric(r)) {
            double rv = toDouble(r);
            if (rv == 0.0) throw PyErrors.error("ZeroDivisionError", "float floor division by zero");
            return new PyFloat(Math.floor(toDouble(l) / rv));
        }
        PyObject dunder = tryBinaryDunder(rawL, rawR, "__floordiv__", "__rfloordiv__");
        if (dunder != null) return dunder;
        throw PyErrors.error("TypeError", "unsupported operand types for //");
    }

    public static PyObject mod(PyObject rawL, PyObject rawR) {
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            if (ri.value() == 0) throw PyErrors.error("ZeroDivisionError", "integer modulo by zero");
            return PyInt.of(Math.floorMod(li.value(), ri.value()));
        }
        if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
            BigInteger ba = l instanceof PyInt pi ? BigInteger.valueOf(pi.value()) : ((BigInt) l).value();
            BigInteger bb = r instanceof PyInt pi ? BigInteger.valueOf(pi.value()) : ((BigInt) r).value();
            return fromBigInteger(PyOps.floorMod(ba, bb));
        }
        if (isNumeric(l) && isNumeric(r)) {
            double rv = toDouble(r), lv = toDouble(l);
            if (rv == 0.0) throw PyErrors.error("ZeroDivisionError", "float modulo by zero");
            return new PyFloat(lv - Math.floor(lv / rv) * rv);
        }
        PyObject dunder = tryBinaryDunder(rawL, rawR, "__mod__", "__rmod__");
        if (dunder != null) return dunder;
        throw PyErrors.error("TypeError", "unsupported operand types for %");
    }

    private static PyObject repeatBytes(byte[] src, PyObject countObj, ExecutionLimits limits, boolean immutable) {
        long count;
        if (countObj instanceof PyInt pi) count = pi.value();
        else {
            BigInteger bi = ((BigInt) countObj).value();
            if (bi.signum() <= 0 || src.length == 0) count = 0;
            else {
                try { count = bi.intValueExact(); }
                catch (ArithmeticException ae) {
                    throw PyErrors.error("OverflowError", "Bytes length exceeds maximum allowed limit: " + limits.maxStringLength());
                }
            }
        }
        if (count <= 0 || src.length == 0) {
            return immutable ? PyBytes.EMPTY : new PyByteArray();
        }
        long newLen = (long) src.length * count;
        if (limits != null && newLen > limits.maxStringLength()) {
            throw PyErrors.error("MemoryError", "Bytes length exceeds maximum allowed limit: " + limits.maxStringLength());
        }
        byte[] out = new byte[(int) newLen];
        for (int c = 0; c < count; c++) {
            System.arraycopy(src, 0, out, c * src.length, src.length);
        }
        return immutable ? PyBytes.wrapOwned(out) : new PyByteArray(out);
    }

    public static PyObject pow(PyObject rawL, PyObject rawR, ExecutionLimits limits) {
        var l = unwrapBool(rawL);
        var r = unwrapBool(rawR);
        if (!isNumeric(l) || !isNumeric(r)) {
            PyObject dunder = tryBinaryDunder(rawL, rawR, "__pow__", "__rpow__");
            if (dunder != null) return dunder;
            throw PyErrors.error("TypeError", "unsupported operand types for **");
        }
        if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
            BigInteger base = l instanceof PyInt pi ? BigInteger.valueOf(pi.value()) : ((BigInt) l).value();
            BigInteger exp = r instanceof PyInt pi ? BigInteger.valueOf(pi.value()) : ((BigInt) r).value();
            if (limits != null && exp.compareTo(BigInteger.valueOf(limits.maxExponent())) > 0) {
                throw PyErrors.error("ValueError", "Exponent exceeds maximum allowed limit: " + limits.maxExponent());
            }
            if (exp.compareTo(BigInteger.ZERO) < 0) {
                double baseD = toDouble(l instanceof PyInt ? l : new BigInt(base));
                double expD = exp.doubleValue();
                double res = Math.pow(baseD, expD);
                if (Double.isInfinite(res) && !Double.isInfinite(baseD)) {
                    throw PyErrors.error("OverflowError", "(34, 'Numerical result out of range')");
                }
                return new PyFloat(res);
            }
            int expInt = exp.intValueExact();
            return fromBigInteger(base.pow(expInt));
        }
        double exp = toDouble(r);
        if (limits != null && exp > limits.maxExponent()) {
            throw PyErrors.error("ValueError", "Exponent exceeds maximum allowed limit: " + limits.maxExponent());
        }
        double base = toDouble(l);
        double res = Math.pow(base, exp);
        if (Double.isInfinite(res) && !Double.isInfinite(base)) {
            throw PyErrors.error("OverflowError", "(34, 'Numerical result out of range')");
        }
        return new PyFloat(res);
    }
}
