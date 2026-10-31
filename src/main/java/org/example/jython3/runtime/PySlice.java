package org.example.jython3.runtime;

import java.math.BigInteger;

/**
 * Represents a slice object created by extended subscripting syntax (e.g. [start:stop:step]).
 */
public record PySlice(PyObject start, PyObject stop, PyObject step) implements PyObject {

    public PySlice(PyObject start, PyObject stop) {
        this(start, stop, new PyNull());
    }

    public static record Indices(int start, int stop, int step, int length) {}

    /**
     * Extracts a slice index from a PyObject, rejecting floats and handling BigInt safely.
     * <p>
     * Returns a {@code BigInteger} so the caller can clamp to sequence bounds
     * without silent truncation.  {@code PyNull} (None) is handled by the caller
     * before this method is called.
     *
     * @throws InterpreterException TypeError if the value is a float or non-integer type
     */
    private static BigInteger sliceIndex(PyObject o) {
        if (o instanceof PyInt i) {
            return BigInteger.valueOf(i.value());
        }
        if (o instanceof PyBool b) {
            return b.value() ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (o instanceof BigInt bi) {
            return bi.value();
        }
        if (o instanceof PyFloat) {
            throw PyErrors.error("TypeError",
                    "slice indices must be integers or None or have an __index__ method");
        }
        throw PyErrors.error("TypeError",
                "slice indices must be integers or None or have an __index__ method");
    }

    /**
     * Clamps a BigInteger value into the range [lo, hi] and returns it as int.
     * Values outside int range are clamped before conversion so there is never
     * any silent truncation.
     */
    private static int clampToInt(BigInteger val, int lo, int hi) {
        BigInteger bLo = BigInteger.valueOf(lo);
        BigInteger bHi = BigInteger.valueOf(hi);
        if (val.compareTo(bLo) < 0) return lo;
        if (val.compareTo(bHi) > 0) return hi;
        return val.intValue();
    }

    /**
     * Computes concrete start, stop, step, and length for a sequence of length {@code seqLen}.
     * Follows exact CPython 3 {@code slice.indices()} semantics.
     *
     * <ul>
     *   <li>Float bounds are rejected with {@code TypeError}.</li>
     *   <li>BigInt bounds are clamped to sequence bounds (no truncation).</li>
     *   <li>Step=0 raises {@code ValueError}.</li>
     * </ul>
     */
    public Indices computeIndices(int seqLen) {
        // --- Step ---
        int sStep = 1;
        if (!(step instanceof PyNull)) {
            BigInteger rawStep = sliceIndex(step);
            if (rawStep.signum() == 0) {
                throw PyErrors.error("ValueError", "slice step cannot be zero");
            }
            // CPython clamps extremely large steps; any |step| > seqLen simply
            // means at most one element is selected, so clamping to int range
            // with sign preserved is correct.
            sStep = clampToInt(rawStep, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        int sStart;
        int sStop;

        if (sStep > 0) {
            // --- Positive step ---
            if (start instanceof PyNull) {
                sStart = 0;
            } else {
                BigInteger raw = sliceIndex(start);
                if (raw.signum() < 0) raw = raw.add(BigInteger.valueOf(seqLen));
                sStart = clampToInt(raw, 0, seqLen);
            }

            if (stop instanceof PyNull) {
                sStop = seqLen;
            } else {
                BigInteger raw = sliceIndex(stop);
                if (raw.signum() < 0) raw = raw.add(BigInteger.valueOf(seqLen));
                sStop = clampToInt(raw, 0, seqLen);
            }
        } else {
            // --- Negative step ---
            if (start instanceof PyNull) {
                sStart = seqLen - 1;
            } else {
                BigInteger raw = sliceIndex(start);
                if (raw.signum() < 0) raw = raw.add(BigInteger.valueOf(seqLen));
                // For negative step, start clamps to [-1, seqLen-1]
                sStart = clampToInt(raw, -1, seqLen - 1);
            }

            if (stop instanceof PyNull) {
                sStop = -1;
            } else {
                BigInteger raw = sliceIndex(stop);
                if (raw.signum() < 0) raw = raw.add(BigInteger.valueOf(seqLen));
                // For negative step, stop clamps to [-1, seqLen-1]
                sStop = clampToInt(raw, -1, seqLen - 1);
            }
        }

        // --- Compute result length ---
        int sLen = 0;
        if (sStep > 0) {
            if (sStart < sStop) {
                sLen = 1 + (sStop - 1 - sStart) / sStep;
            }
        } else {
            if (sStart > sStop) {
                sLen = 1 + (sStart - 1 - sStop) / (-sStep);
            }
        }

        return new Indices(sStart, sStop, sStep, sLen);
    }

    @Override
    public String toString() {
        return "slice(" + start + ", " + stop + ", " + step + ")";
    }
}
