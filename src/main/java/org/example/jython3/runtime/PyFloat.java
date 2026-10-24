package org.example.jython3.runtime;



public record PyFloat(double value) implements PyObject {
    @Override public String toString() {
        if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
        if (Double.isNaN(value)) return "nan";
        return (value == Math.floor(value) && !Double.isInfinite(value)) ? (long) value + ".0" : String.valueOf(value);
    }
}
