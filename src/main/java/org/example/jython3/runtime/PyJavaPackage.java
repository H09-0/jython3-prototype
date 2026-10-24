package org.example.jython3.runtime;


import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.security.JavaAccessPolicy;

/** Represents a Java Package navigation point (e.g. java.lang or java.util). */
public record PyJavaPackage(String packageName, JavaAccessPolicy policy) implements PyObject {
    public PyObject getMember(String name) {
        String fullName = packageName.isEmpty() ? name : packageName + "." + name;
        PyObject resolved = JavaBridge.resolveClassOrPackage(fullName, policy);
        if (resolved != null) return resolved;
        throw JavaBridge.denied(fullName);
    }

    @Override public String toString() {
        return "<java package '" + packageName + "'>";
    }
}
