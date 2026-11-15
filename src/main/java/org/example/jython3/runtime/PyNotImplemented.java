package org.example.jython3.runtime;

/**
 * Python {@code NotImplemented} singleton returned by binary/comparison dunder
 * methods when an operation is not supported for the given operand types.
 */
public final class PyNotImplemented implements PyObject {
    public static final PyNotImplemented INSTANCE = new PyNotImplemented();

    private PyNotImplemented() {}

    @Override
    public String toString() {
        return "NotImplemented";
    }
}
