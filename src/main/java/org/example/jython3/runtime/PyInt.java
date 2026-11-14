package org.example.jython3.runtime;

public record PyInt(int value) implements PyObject {

    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 256;
    private static final PyInt[] CACHE = new PyInt[CACHE_HIGH - CACHE_LOW + 1];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new PyInt(i + CACHE_LOW);
        }
    }

    public static final PyInt ZERO = of(0);
    public static final PyInt ONE = of(1);
    public static final PyInt MINUS_ONE = of(-1);

    public static PyInt of(int value) {
        if (value >= CACHE_LOW && value <= CACHE_HIGH) {
            return CACHE[value - CACHE_LOW];
        }
        return new PyInt(value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
