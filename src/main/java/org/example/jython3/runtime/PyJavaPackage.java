package org.example.jython3.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;

/** Represents a Java Package navigation point (e.g. java.lang or java.util). */
public record PyJavaPackage(String packageName) implements PyObject {
    public PyObject getMember(String name) {
        String fullName = packageName.isEmpty() ? name : packageName + "." + name;
        JavaBridge.checkSecurity(fullName);
        try {
            PyObject resolved = JavaBridge.resolveClassOrPackage(fullName);
            if (resolved != null) return resolved;
        } catch (Exception e) {
            throw e;
        }
        throw new InterpreterException("SecurityError: access to '" + fullName + "' is restricted");
    }

    @Override public String toString() {
        return "<java package '" + packageName + "'>";
    }
}
