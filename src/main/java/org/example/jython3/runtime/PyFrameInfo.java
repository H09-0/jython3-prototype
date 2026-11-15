package org.example.jython3.runtime;

/**
 * Lightweight Python {@code frame} snapshot object exposed via {@code tb.tb_frame}.
 */
public record PyFrameInfo(PyCodeInfo fCode, int fLineno) implements PyObject {

    public PyObject pythonAttribute(String name) {
        return switch (name) {
            case "f_code" -> fCode;
            case "f_lineno" -> PyInt.of(fLineno);
            default -> null;
        };
    }

    @Override public String toString() {
        return "<frame at file '" + fCode.coFilename() + "', line " + fLineno + ", code " + fCode.coName() + ">";
    }
}
