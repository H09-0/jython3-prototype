package org.example.jython3.runtime;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Lazy, immutable Python 3 {@code range(start, stop, step)} sequence.
 * Occupies O(1) memory regardless of range length and supports O(1)
 * {@code len()}, {@code in}, indexing, slicing, {@code reversed()},
 * {@code count()}, {@code index()}, equality, and hashing.
 */
public final class PyRange implements PyObject {

    private final BigInteger start;
    private final BigInteger stop;
    private final BigInteger step;
    private final BigInteger length;

    public PyRange(BigInteger start, BigInteger stop, BigInteger step) {
        if (step.signum() == 0) {
            throw PyErrors.error("ValueError", "range() arg 3 must not be zero");
        }
        this.start = Objects.requireNonNull(start);
        this.stop = Objects.requireNonNull(stop);
        this.step = Objects.requireNonNull(step);
        this.length = computeLength(start, stop, step);
    }

    private static BigInteger computeLength(BigInteger start, BigInteger stop, BigInteger step) {
        if (step.signum() > 0) {
            if (start.compareTo(stop) >= 0) return BigInteger.ZERO;
            return stop.subtract(start).subtract(BigInteger.ONE).divide(step).add(BigInteger.ONE);
        } else {
            if (start.compareTo(stop) <= 0) return BigInteger.ZERO;
            return start.subtract(stop).subtract(BigInteger.ONE).divide(step.negate()).add(BigInteger.ONE);
        }
    }

    public PyObject startPy() { return toPyInt(start); }
    public PyObject stopPy() { return toPyInt(stop); }
    public PyObject stepPy() { return toPyInt(step); }
    public BigInteger lengthBig() { return length; }

    public int lenInt() {
        try {
            return length.intValueExact();
        } catch (ArithmeticException ae) {
            throw PyErrors.error("OverflowError", "Python int too large to convert to Java int");
        }
    }

    public boolean isEmpty() {
        return length.signum() == 0;
    }

    public boolean contains(PyObject item) {
        BigInteger v = extractExactInt(item);
        if (v == null || length.signum() == 0) return false;
        if (step.signum() > 0) {
            if (v.compareTo(start) < 0 || v.compareTo(stop) >= 0) return false;
            return v.subtract(start).mod(step).signum() == 0;
        } else {
            if (v.compareTo(start) > 0 || v.compareTo(stop) <= 0) return false;
            return start.subtract(v).mod(step.negate()).signum() == 0;
        }
    }

    public PyObject indexOf(PyObject item) {
        BigInteger v = extractExactInt(item);
        if (v == null || !contains(item)) {
            throw PyErrors.error("ValueError", item + " is not in range");
        }
        return toPyInt(v.subtract(start).divide(step));
    }

    public PyObject getAt(PyObject idxObj) {
        BigInteger idx = extractExactInt(idxObj);
        if (idx == null) {
            throw PyErrors.error("TypeError", "range indices must be integers or slices, not " + PyOps.typeName(idxObj));
        }
        if (idx.signum() < 0) idx = idx.add(length);
        if (idx.signum() < 0 || idx.compareTo(length) >= 0) {
            throw PyErrors.error("IndexError", "range object index out of range");
        }
        return toPyInt(start.add(step.multiply(idx)));
    }

    public PyRange slice(PySlice sl) {
        int len = lenInt();
        PySlice.Indices idx = sl.computeIndices(len);
        BigInteger newStart = start.add(step.multiply(BigInteger.valueOf(idx.start())));
        BigInteger newStep = step.multiply(BigInteger.valueOf(idx.step()));
        BigInteger newStop = newStart.add(newStep.multiply(BigInteger.valueOf(idx.length())));
        return new PyRange(newStart, newStop, newStep);
    }

    public PyIterator iterator() {
        if (length.signum() == 0) {
            return PyIterator.ofIntRange(0L, 1L, 0L);
        }
        if (length.bitLength() < 63) {
            final long curStart = start.longValue();
            final long curStep = step.longValue();
            final long count = length.longValue();
            if (start.bitLength() < 32 && step.bitLength() < 32) {
                BigInteger last = start.add(step.multiply(length.subtract(BigInteger.ONE)));
                if (last.bitLength() < 32) {
                    return PyIterator.ofIntRange(curStart, curStep, count);
                }
            }
            return new PyIterator(new Iterator<PyObject>() {
                private long idx = 0;
                private long val = curStart;

                @Override public boolean hasNext() { return idx < count; }
                @Override public PyObject next() {
                    if (idx >= count) throw new NoSuchElementException();
                    long out = val;
                    val += curStep;
                    idx++;
                    return (out >= Integer.MIN_VALUE && out <= Integer.MAX_VALUE)
                            ? PyInt.of((int) out)
                            : new BigInt(BigInteger.valueOf(out));
                }
            });
        }
        return new PyIterator(new Iterator<PyObject>() {
            private BigInteger idx = BigInteger.ZERO;
            private BigInteger val = start;

            @Override public boolean hasNext() { return idx.compareTo(length) < 0; }
            @Override public PyObject next() {
                if (!hasNext()) throw new NoSuchElementException();
                BigInteger out = val;
                val = val.add(step);
                idx = idx.add(BigInteger.ONE);
                return toPyInt(out);
            }
        });
    }

    public PyIterator reversedIterator() {
        if (isEmpty()) {
            return new PyIterator(java.util.Collections.emptyIterator());
        }
        BigInteger last = start.add(step.multiply(length.subtract(BigInteger.ONE)));
        return new PyRange(last, start.subtract(step), step.negate()).iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PyRange other)) return false;
        if (!this.length.equals(other.length)) return false;
        if (this.length.signum() == 0) return true;
        if (!this.start.equals(other.start)) return false;
        if (this.length.equals(BigInteger.ONE)) return true;
        return this.step.equals(other.step);
    }

    @Override
    public int hashCode() {
        if (length.signum() == 0) return Objects.hash(0);
        if (length.equals(BigInteger.ONE)) return Objects.hash(1, start);
        return Objects.hash(length, start, step);
    }

    @Override
    public String toString() {
        if (step.equals(BigInteger.ONE)) {
            return "range(" + start + ", " + stop + ")";
        }
        return "range(" + start + ", " + stop + ", " + step + ")";
    }

    private static BigInteger extractExactInt(PyObject o) {
        if (o instanceof PyInt i) return BigInteger.valueOf(i.value());
        if (o instanceof BigInt bi) return bi.value();
        if (o instanceof PyBool b) return b.value() ? BigInteger.ONE : BigInteger.ZERO;
        return null;
    }

    private static PyObject toPyInt(BigInteger bi) {
        if (bi.bitLength() < 32) return PyInt.of(bi.intValue());
        return new BigInt(bi);
    }
}
