package org.example.jython3.runtime;

import java.util.Objects;

/**
 * Built-in {@code @staticmethod} descriptor.
 */
public record PyStaticMethod(PyObject callable) implements PyObject {
    public PyStaticMethod {
        Objects.requireNonNull(callable, "callable");
    }

    @Override
    public String toString() {
        return "<staticmethod(" + callable + ")>";
    }
}
