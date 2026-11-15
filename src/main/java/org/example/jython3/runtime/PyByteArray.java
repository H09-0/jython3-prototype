package org.example.jython3.runtime;

import java.util.Arrays;

/**
 * Mutable, unhashable Python 3 {@code bytearray} sequence.
 */
public final class PyByteArray implements PyObject {

    private byte[] data;
    private int size;

    public PyByteArray() {
        this.data = new byte[16];
        this.size = 0;
    }

    public PyByteArray(byte[] initial) {
        if (initial == null || initial.length == 0) {
            this.data = new byte[16];
            this.size = 0;
        } else {
            this.data = initial.clone();
            this.size = initial.length;
        }
    }

    public int length() { return size; }

    public byte[] copyBytes() {
        return Arrays.copyOf(data, size);
    }

    private void ensureCapacity(int minCap) {
        if (minCap <= data.length) return;
        int newCap = Math.max(minCap, data.length << 1);
        data = Arrays.copyOf(data, newCap);
    }

    public void append(int byteVal) {
        ensureCapacity(size + 1);
        data[size++] = (byte) byteVal;
    }

    public void extend(byte[] more, int maxLimit) {
        if ((long) size + more.length > maxLimit) {
            throw PyErrors.error("MemoryError", "bytearray length exceeds maximum limit: " + maxLimit);
        }
        ensureCapacity(size + more.length);
        System.arraycopy(more, 0, data, size, more.length);
        size += more.length;
    }

    public void clear() { size = 0; }

    public int pop(int idx) {
        if (size == 0) throw PyErrors.error("IndexError", "pop from empty bytearray");
        if (idx < 0) idx += size;
        if (idx < 0 || idx >= size) throw PyErrors.error("IndexError", "pop index out of range");
        int out = data[idx] & 0xFF;
        System.arraycopy(data, idx + 1, data, idx, size - idx - 1);
        size--;
        return out;
    }

    public void reverse() {
        for (int i = 0, j = size - 1; i < j; i++, j--) {
            byte tmp = data[i];
            data[i] = data[j];
            data[j] = tmp;
        }
    }

    public PyObject getAt(PyObject idxObj) {
        int i = PyBytes.extractIntIndex(idxObj);
        if (i < 0) i += size;
        if (i < 0 || i >= size) throw PyErrors.error("IndexError", "bytearray index out of range");
        return PyInt.of(data[i] & 0xFF);
    }

    public void setAt(PyObject idxObj, PyObject valObj) {
        if (idxObj instanceof PySlice sl) {
            byte[] repl = PyBytes.extractBuffer(valObj);
            PySlice.Indices idx = sl.computeIndices(size);
            if (idx.step() != 1) {
                if (repl.length != idx.length()) {
                    throw PyErrors.error("ValueError",
                            "attempt to assign bytes of size " + repl.length + " to extended slice of size " + idx.length());
                }
                for (int i = idx.start(), c = 0; c < idx.length(); i += idx.step(), c++) {
                    data[i] = repl[c];
                }
                return;
            }
            int tailLen = size - idx.stop();
            int newSize = idx.start() + repl.length + tailLen;
            ensureCapacity(newSize);
            System.arraycopy(data, idx.stop(), data, idx.start() + repl.length, tailLen);
            System.arraycopy(repl, 0, data, idx.start(), repl.length);
            size = newSize;
            return;
        }
        int i = PyBytes.extractIntIndex(idxObj);
        if (i < 0) i += size;
        if (i < 0 || i >= size) throw PyErrors.error("IndexError", "bytearray index out of range");
        data[i] = (byte) PyBytes.extractByteValue(valObj);
    }

    public PyByteArray slice(PySlice sl) {
        PySlice.Indices idx = sl.computeIndices(size);
        byte[] out = new byte[idx.length()];
        for (int i = idx.start(), c = 0; c < idx.length(); i += idx.step(), c++) {
            out[c] = data[i];
        }
        return new PyByteArray(out);
    }

    public boolean contains(PyObject item) {
        byte[] snap = copyBytes();
        if (item instanceof PyInt || item instanceof PyBool || item instanceof BigInt) {
            int b = PyBytes.extractByteValue(item);
            for (byte v : snap) if ((v & 0xFF) == b) return true;
            return false;
        }
        return PyBytes.indexOfBytes(snap, PyBytes.extractBuffer(item)) >= 0;
    }

    public String decode(String encoding, String errors) {
        return PyBytes.wrapOwned(copyBytes()).decode(encoding, errors);
    }

    public String toHex() {
        return PyBytes.wrapOwned(copyBytes()).toHex();
    }

    public PyIterator iterator() {
        return PyBytes.byteIterator(copyBytes());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        byte[] snap = copyBytes();
        if (o instanceof PyByteArray other) return Arrays.equals(snap, other.copyBytes());
        if (o instanceof PyBytes other) return Arrays.equals(snap, other.bytesUnsafe());
        return false;
    }

    @Override
    public String toString() {
        return PyBytes.formatBytesRepr(copyBytes(), "bytearray(b", ")");
    }
}
