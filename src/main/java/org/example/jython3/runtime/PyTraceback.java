package org.example.jython3.runtime;

/**
 * Python {@code traceback} object exposed via {@code exc.__traceback__} and the
 * third argument to {@code __exit__(exc_type, exc_val, exc_tb)}.
 *
 * <p>Nodes are linked outermost-first ({@code tb_next} walks toward the raise site),
 * matching CPython.
 */
public record PyTraceback(PyFrameInfo tbFrame, int tbLineno, PyTraceback tbNext) implements PyObject {

    public PyObject pythonAttribute(String name) {
        return switch (name) {
            case "tb_frame" -> tbFrame;
            case "tb_lineno" -> PyInt.of(tbLineno);
            case "tb_next" -> tbNext != null ? tbNext : new PyNull();
            case "tb_lasti" -> PyInt.of(0);
            default -> null;
        };
    }

    @Override public String toString() {
        return "<traceback object for " + tbFrame.fCode().coName() + " line " + tbLineno + ">";
    }
}
