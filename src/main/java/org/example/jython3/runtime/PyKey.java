package org.example.jython3.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Hashable wrapper around Python objects for dictionary keys and set elements.
 *
 * <p>Enforces Python 3 hashing and equality contracts:
 * <ul>
 *   <li>1 == 1.0 == True  -> all produce the exact same hash code and are equal.
 *   <li>0 == 0.0 == False -> all produce the exact same hash code and are equal.
 *   <li>BigInt and PyInt equal to integer-valued PyFloat share identical hash and equality.
 *   <li>Unhashable types (PyList, PyDict) throw {@code TypeError: unhashable type}.
 * </ul>
 */
public final class PyKey {

    private final PyObject object;
    private final int hashCode;

    public PyKey(PyObject object) {
        this.object = Objects.requireNonNull(object, "object");
        this.hashCode = computeHash(object);
    }

    public PyObject object() {
        return object;
    }

    private static int computeHash(PyObject obj) {
        if (obj instanceof PyList || obj instanceof PyDict) {
            throw PyErrors.error("TypeError", "unhashable type: '" + PyOps.typeName(obj) + "'");
        }
        if (obj instanceof PyNull) {
            return 0;
        }
        if (obj instanceof PyBool b) {
            return b.value() ? 1 : 0;
        }
        if (obj instanceof PyInt i) {
            return Integer.hashCode(i.value());
        }
        if (obj instanceof BigInt bi) {
            // If it fits in int, hash like int
            BigInteger bVal = bi.value();
            if (bVal.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                bVal.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                return Integer.hashCode(bVal.intValue());
            }
            return bVal.hashCode();
        }
        if (obj instanceof PyFloat f) {
            double d = f.value();
            if (d == 0.0) return 0; // 0.0 and -0.0 both hash to 0
            if (Double.isNaN(d)) return 0;
            // If the float has an exact integral value, its hash must equal the integer hash!
            if (!Double.isInfinite(d) && Math.floor(d) == d) {
                BigDecimal bd = new BigDecimal(d);
                try {
                    BigInteger bi = bd.toBigIntegerExact();
                    if (bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                        bi.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                        return Integer.hashCode(bi.intValue());
                    }
                    return bi.hashCode();
                } catch (Exception ignored) {}
            }
            return Double.hashCode(d);
        }
        if (obj instanceof PyStr s) {
            return s.value().hashCode();
        }
        return obj.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof PyKey other) {
            return PyOps.pyEquals(this.object, other.object);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return String.valueOf(object);
    }
}
