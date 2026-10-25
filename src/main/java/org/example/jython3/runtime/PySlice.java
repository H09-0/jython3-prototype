package org.example.jython3.runtime;

/**
 * Represents a slice object created by extended subscripting syntax (e.g. [start:stop:step]).
 */
public record PySlice(PyObject start, PyObject stop, PyObject step) implements PyObject {

    public PySlice(PyObject start, PyObject stop) {
        this(start, stop, new PyNull());
    }

    public static record Indices(int start, int stop, int step, int length) {}

    /**
     * Computes concrete start, stop, step, and length for a sequence of length {@code seqLen}.
     * Follows exact Python 3 slice.indices() semantics.
     */
    public Indices computeIndices(int seqLen) {
        int sStep = 1;
        if (!(step instanceof PyNull)) {
            sStep = PyOps.toInt(step);
            if (sStep == 0) {
                throw PyErrors.error("ValueError", "slice step cannot be zero");
            }
        }

        int sStart;
        int sStop;

        if (sStep > 0) {
            if (start instanceof PyNull) {
                sStart = 0;
            } else {
                sStart = PyOps.toInt(start);
                if (sStart < 0) sStart += seqLen;
                if (sStart < 0) sStart = 0;
                if (sStart > seqLen) sStart = seqLen;
            }

            if (stop instanceof PyNull) {
                sStop = seqLen;
            } else {
                sStop = PyOps.toInt(stop);
                if (sStop < 0) sStop += seqLen;
                if (sStop < 0) sStop = 0;
                if (sStop > seqLen) sStop = seqLen;
            }
        } else {
            if (start instanceof PyNull) {
                sStart = seqLen - 1;
            } else {
                sStart = PyOps.toInt(start);
                if (sStart < 0) sStart += seqLen;
                if (sStart < -1) sStart = -1;
                if (sStart >= seqLen) sStart = seqLen - 1;
            }

            if (stop instanceof PyNull) {
                sStop = -1;
            } else {
                sStop = PyOps.toInt(stop);
                if (sStop < 0) sStop += seqLen;
                if (sStop < -1) sStop = -1;
                if (sStop >= seqLen) sStop = seqLen - 1;
            }
        }

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
