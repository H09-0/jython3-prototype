package org.example.jython3.field;

import org.example.jython3.Jython3Interpreter;

/** A self-contained real-world Python scenario with verification assertions. */
public interface FieldScenario {
    String name();
    String pythonCode();
    void verify(Jython3Interpreter interp);

    static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError("Field scenario assertion failed: " + message);
        }
    }
}
