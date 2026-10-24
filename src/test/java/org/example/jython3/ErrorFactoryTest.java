package org.example.jython3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.junit.jupiter.api.Test;

/**
 * Guards the single error factory.
 *
 * <p>Only {@link PyErrors} may construct {@link InterpreterException}; any other
 * file that does so fails the build. The legacy string constructor remains for
 * the tests below, but production code passes a type and message instead.
 */
class ErrorFactoryTest {

    @Test
    void onlyTheFactoryConstructsInterpreterException() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!text.contains("new InterpreterException(")) continue;
                String name = file.getFileName().toString();
                if (!name.equals("PyErrors.java")) offenders.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "Use PyErrors.error(type, message) in: " + offenders);
    }

    @Test
    void factoryDoesNotUseTheLegacyStringConstructor() throws IOException {
        String text = Files.readString(Path.of("src/main/java/org/example/jython3/runtime/PyErrors.java"));
        assertFalse(text.contains("new InterpreterException(\""),
                "PyErrors must pass values, not formatted strings");
    }

    @Test
    void errorValueKeepsTheGivenPythonValue() {
        PyException value = PyException.of("UserError", "custom");
        assertSame(value, PyErrors.errorValue(value).value());
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
        assertTrue(PyErrors.matches(value, "BaseException"));
        assertFalse(PyErrors.matches(value, "TypeError"));
    }

    @Test
    void exceptionDoesNotCatchTheBaseExceptionOnlyTypes() {
        for (String type : List.of("SystemExit", "KeyboardInterrupt", "GeneratorExit")) {
            var value = PyException.of(type, "");
            assertFalse(PyErrors.matches(value, "Exception"), type + " must not be caught by Exception");
            assertTrue(PyErrors.matches(value, "BaseException"), type + " must be caught by BaseException");
        }
    }

    @Test
    void runtimeTypesInheritFromException() {
        for (String type : List.of("SecurityError", "JavaException", "JavaError", "CustomError")) {
            assertTrue(PyErrors.matches(PyException.of(type, ""), "Exception"), type);
        }
        assertFalse(PyErrors.matches(PyException.of("CustomError", ""), "ValueError"));
    }

    @Test
    void builtinHierarchyIsUsedForMatching() {
        assertTrue(PyErrors.matches(PyException.of("ZeroDivisionError", ""), "ArithmeticError"));
        assertTrue(PyErrors.matches(PyException.of("IndexError", ""), "LookupError"));
        assertTrue(PyErrors.matches(PyException.of("FileNotFoundError", ""), "OSError"));
        assertTrue(PyErrors.matches(PyException.of("IndentationError", ""), "SyntaxError"));
        assertFalse(PyErrors.matches(PyException.of("ValueError", ""), "ArithmeticError"));
        assertFalse(PyErrors.matches(PyException.of("BaseException", ""), "Exception"));
    }

    @Test
    void fromJavaKeepsTheOriginalThrowable() {
        Throwable original = new IllegalStateException("boom");
        InterpreterException e = PyErrors.fromJava(new java.lang.reflect.InvocationTargetException(original));
        PyException value = (PyException) e.value();
        assertEquals("JavaException", value.typeName());
        assertSame(original, value.javaCause());
        assertEquals("java.lang.IllegalStateException: boom", value.message());
        assertTrue(PyErrors.matches(value, "JavaException"));
        assertTrue(PyErrors.matches(value, "Exception"));
        assertFalse(PyErrors.matches(value, "ValueError"));
    }

    @Test
    void fromJavaMapsKnownThrowablesToPythonTypes() {
        var e = PyErrors.fromJava(new NumberFormatException("bad number"));
        assertEquals("ValueError", e.typeName());
        assertTrue(PyErrors.matches(e.value(), "ValueError"));
        assertTrue(PyErrors.matches(e.value(), "Exception"));
        assertFalse(PyErrors.matches(e.value(), "JavaException"));
    }

    @Test
    void fromJavaDoesNotDoubleWrapPythonErrors() {
        InterpreterException original = PyErrors.error("TypeError", "from python");
        assertSame(original, PyErrors.fromJava(original));
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
}
