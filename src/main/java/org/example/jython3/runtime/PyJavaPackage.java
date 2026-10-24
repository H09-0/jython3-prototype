package org.example.jython3.runtime;


import org.example.jython3.bridge.JavaBridge;

/** Represents a Java Package navigation point (e.g. java.lang or java.util). */
public record PyJavaPackage(String packageName) implements PyObject {
    public PyObject getMember(String name) {
        String fullName = packageName.isEmpty() ? name : packageName + "." + name;
        JavaBridge.checkSecurity(fullName);
        PyObject resolved = JavaBridge.resolveClassOrPackage(fullName);
        if (resolved != null) return resolved;
        throw PyErrors.error("SecurityError", "access to '" + fullName + "' is restricted");
    }

    @Override public String toString() {
        return "<java package '" + packageName + "'>";
    }
}
