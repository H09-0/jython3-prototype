package org.example.jython3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.junit.jupiter.api.Test;

/**
 * Guards the migration to a single error factory.
 *
 * <p>Files listed in {@link #PENDING} still build errors from formatted
 * strings. Each migration commit lowers their counts and eventually removes
 * them. A new file that constructs {@link InterpreterException} directly, or a
 * file whose count grows, fails the build.
 */
class ErrorFactoryTest {

    /** Files that still use the legacy string constructor, with their current site counts. */
    private static final Map<String, Integer> PENDING = Map.of(
            "Jython3Interpreter.java", 72,
            "BuiltinFunctions.java", 35,
            "JavaBridge.java", 10,
            "PyJavaObject.java", 8,
            "BuiltinModules.java", 5,
            "PyJavaClass.java", 5,
            "PyJavaMethod.java", 3,
            "PyJavaPackage.java", 1);

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
                if (!PENDING.containsKey(name)) offenders.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "Use PyErrors.error(type, message) in: " + offenders);
    }

    @Test
    void legacySiteCountsOnlyShrink() throws IOException {
        for (var entry : PENDING.entrySet()) {
            Path file = findByName(Path.of("src/main/java"), entry.getKey());
            assertNotNull(file, entry.getKey() + " is missing");
            int actual = countOccurrences(Files.readString(file), "new InterpreterException(");
            assertTrue(actual <= entry.getValue(),
                    entry.getKey() + " grew from " + entry.getValue() + " to " + actual
                            + " direct constructions; use PyErrors.error");
        }
    }

    @Test
    void factoryCarriesAValue() {
        InterpreterException e = PyErrors.error("ValueError", "bad input");
        assertInstanceOf(PyException.class, e.value());
        assertEquals("ValueError", e.typeName());
        assertEquals("ValueError: bad input", e.getMessage());
    }

    @Test
    void factoryDoesNotDuplicateTheTypePrefix() {
        InterpreterException e = PyErrors.error("ValueError", "ValueError: bad input");
        assertEquals("ValueError: bad input", e.getMessage());
        assertEquals("bad input", ((PyException) e.value()).message());
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
    void matchingUsesTheCarriedValue() {
        var value = PyException.of("ValueError", "bad");
        assertTrue(PyErrors.matches(value, "ValueError"));
        assertTrue(PyErrors.matches(value, "Exception"));
        assertFalse(PyErrors.matches(value, "TypeError"));
    }

    @Test
    void javaCausesMapToPythonTypes() {
        assertTrue(PyErrors.matches(
                PyException.withJavaCause("JavaException", "x", new NumberFormatException("nope")), "ValueError"));
        assertTrue(PyErrors.matches(
                PyException.withJavaCause("JavaException", "x", new ArrayIndexOutOfBoundsException("nope")), "IndexError"));
        assertFalse(PyErrors.matches(
                PyException.withJavaCause("JavaException", "x", new NumberFormatException("nope")), "TypeError"));
    }

    @Test
    void javaStackTracesAreOffByDefault() {
        assertEquals(0, PyErrors.error("ValueError", "x").getStackTrace().length);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static Path findByName(Path root, String name) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().equals(name)).findFirst().orElse(null);
        }
    }
}
