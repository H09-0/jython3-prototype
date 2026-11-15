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
        var r = unwrapBool(rawR);
        var l = unwrapBool(rawL);
        if (l instanceof PyStr ls && r instanceof PyStr rs) {
            return new PyStr(ls.value() + rs.value());
        } else if (l instanceof PyList ll && r instanceof PyList rl) {
            var items = new ArrayList<>(ll.items());
            items.addAll(rl.items());
            return new PyList(items);
        } else if (l instanceof PyTuple lt && r instanceof PyTuple rt) {
            var items = new ArrayList<>(lt.items());
            items.addAll(rt.items());
            return new PyTuple(items);
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
        throw PyErrors.error("TypeError", "unsupported operand types for arithmetic");
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
        throw PyErrors.error("TypeError", "unsupported operand types for arithmetic");
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
        throw PyErrors.error("TypeError", "unsupported operand types for arithmetic");
    }

    public static PyObject bitwiseAnd(PyObject rawL, PyObject rawR) {
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
}
