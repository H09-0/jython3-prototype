package org.example.jython3.runtime;

public record PyFloat(double value) implements PyObject {
    @Override
    public String toString() {
        if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
        if (Double.isNaN(value)) return "nan";
        String s = Double.toString(value);
        if (s.contains("E")) {
            String[] parts = s.split("E");
            String mantissa = parts[0];
            String exp = parts[1];
            if (mantissa.endsWith(".0")) {
                mantissa = mantissa.substring(0, mantissa.length() - 2);
            }
            if (!exp.startsWith("-")) {
                exp = "+" + exp;
            }
            return mantissa + "e" + exp;
        }
        return s;
    }
}
