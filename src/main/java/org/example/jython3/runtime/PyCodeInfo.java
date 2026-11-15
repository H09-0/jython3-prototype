package org.example.jython3.runtime;

/**
 * Lightweight Python {@code code} metadata object exposed via {@code tb.tb_frame.f_code}.
 */
public record PyCodeInfo(String coName, String coFilename) implements PyObject {

    public PyObject pythonAttribute(String name) {
        return switch (name) {
            case "co_name" -> new PyStr(coName != null ? coName : "<module>");
            case "co_filename" -> new PyStr(coFilename != null ? coFilename : "<stdin>");
            default -> null;
        };
    }

    @Override public String toString() {
        return "<code object " + (coName != null ? coName : "<module>")
                + ", file \"" + (coFilename != null ? coFilename : "<stdin>") + "\">";
    }
}
