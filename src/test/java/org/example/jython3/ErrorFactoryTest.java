package org.example.jython3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.junit.jupiter.api.Test;

/**
 * Guards the migration to a single error factory.
 *
 * <p>Files listed in {@link #PENDING} still build errors from formatted
 * strings. Each migration commit removes one file from the list. A new file
 * that constructs {@link InterpreterException} directly fails the build.
 */
class ErrorFactoryTest {

    /** Files that still use the legacy string constructor. */
    private static final Set<String> PENDING = Set.of(
            "Jython3Interpreter.java",
            "BuiltinFunctions.java",
            "BuiltinModules.java",
            "JavaBridge.java",
            "PyJavaClass.java",
            "PyJavaMethod.java",
            "PyJavaObject.java",
            "PyJavaPackage.java");

    @Test
    void legacyConstructionIsConfinedToPendingFiles() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!text.contains("new InterpreterException(")) continue;
                String name = file.getFileName().toString();
                if (name.equals("PyErrors.java")) continue; // the factory itself
                if (!PENDING.contains(name)) offenders.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "Use PyErrors.error(type, message) in: " + offenders);
    }

    @Test
    void factoryCarriesAValue() {
        InterpreterException e = PyErrors.error("ValueError", "bad input");
        assertInstanceOf(PyException.class, e.value());
        assertEquals("ValueError", e.typeName());
        assertEquals("ValueError: bad input", e.getMessage());
    }

    @Test
    void legacyTextKeepsItsMessageAndGainsAType() {
        InterpreterException e = new InterpreterException("ZeroDivisionError: division by zero");
        assertEquals("ZeroDivisionError", e.typeName());
        assertEquals("ZeroDivisionError: division by zero", e.getMessage());
    }

    @Test
    void legacyTextWithoutATypeStaysUnclassified() {
        InterpreterException e = new InterpreterException("something went wrong");
        assertEquals("Exception", e.typeName());
        assertEquals("something went wrong", e.getMessage());
    }

    @Test
    void javaStackTracesAreOffByDefault() {
        assertEquals(0, PyErrors.error("ValueError", "x").getStackTrace().length);
    }
}
