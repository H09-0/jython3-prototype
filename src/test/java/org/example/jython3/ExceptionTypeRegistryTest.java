package org.example.jython3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.example.jython3.runtime.PyStr;
import org.junit.jupiter.api.Test;

/**
 * Tests the exception-type registry on its own. The registry owns the classes;
 * inheritance lives in {@link PyClass}, and matching follows those classes
 * instead of a separate name table.
 */
class ExceptionTypeRegistryTest {

    @Test
    void builtinHierarchyIsMadeOfRealClasses() {
        ExceptionTypeRegistry registry = ExceptionTypeRegistry.standard();

        assertSame(registry.type("ValueError"), registry.type("ValueError"));
        assertTrue(registry.type("ZeroDivisionError").isSubclassOf(registry.type("ArithmeticError")));
        assertTrue(registry.type("ArithmeticError").isSubclassOf(registry.type("Exception")));
        assertTrue(registry.type("ValueError").isSubclassOf(registry.type("BaseException")));
        assertTrue(registry.type("KeyError").isSubclassOf(registry.type("LookupError")));
        assertFalse(registry.type("ValueError").isSubclassOf(registry.type("ArithmeticError")));
    }

    @Test
    void exceptionDoesNotIncludeTheBaseExceptionOnlyTypes() {
        ExceptionTypeRegistry registry = ExceptionTypeRegistry.standard();

        for (String name : List.of("SystemExit", "KeyboardInterrupt", "GeneratorExit")) {
            assertFalse(registry.type(name).isSubclassOf(registry.type("Exception")), name);
            assertTrue(registry.type(name).isSubclassOf(registry.type("BaseException")), name);
        }
    }

    @Test
    void runtimeTypesAreOrdinaryExceptionClasses() {
        ExceptionTypeRegistry registry = ExceptionTypeRegistry.standard();

        for (String name : List.of("SecurityError", "JavaException", "JavaError")) {
            assertTrue(registry.type(name).isSubclassOf(registry.type("Exception")), name);
        }
        assertTrue(registry.isExceptionClass(registry.type("SecurityError")));
        assertFalse(registry.isExceptionClass(new PyClass("Plain", null, new java.util.HashMap<>())));
    }

    @Test
    void eachRegistryOwnsItsTypeObjects() {
        ExceptionTypeRegistry first = ExceptionTypeRegistry.standard();
        ExceptionTypeRegistry second = ExceptionTypeRegistry.standard();

        assertNotSame(first.type("ValueError"), second.type("ValueError"));
        assertEquals(first.type("ValueError").name(), second.type("ValueError").name());
    }

    @Test
    void unknownNamesBecomeOrdinaryExceptionClasses() {
        ExceptionTypeRegistry registry = ExceptionTypeRegistry.standard();

        PyClass custom = registry.type("CustomError");
        assertSame(custom, registry.type("CustomError"));
        assertTrue(custom.isSubclassOf(registry.type("Exception")));
        assertFalse(custom.isSubclassOf(registry.type("ValueError")));
    }

    @Test
    void valuesCarryTheRegistryClassAndDeriveTheirName() {
        ExceptionTypeRegistry registry = ExceptionTypeRegistry.standard();
        ExceptionTypeRegistry previous = ExceptionTypeRegistry.install(registry);
        try {
            InterpreterException e = PyErrors.error("ValueError", "bad input");
            PyException value = assertInstanceOf(PyException.class, e.value());

            assertSame(registry.type("ValueError"), value.type());
            assertEquals("ValueError", value.typeName());
            assertEquals("bad input", value.message());
            assertEquals(List.of(new PyStr("bad input")), value.args());
        } finally {
            ExceptionTypeRegistry.restore(previous);
        }
    }

    @Test
    void matchingUsesClassIdentityNotNames() {
        ExceptionTypeRegistry first = ExceptionTypeRegistry.standard();
        ExceptionTypeRegistry second = ExceptionTypeRegistry.standard();
        PyException value = new PyException(first.type("ValueError"), "x");

        assertTrue(PyErrors.matches(value, first.type("ValueError")));
        assertTrue(PyErrors.matches(value, first.type("Exception")));
        assertFalse(PyErrors.matches(value, first.type("TypeError")));
        assertFalse(PyErrors.matches(value, second.type("ValueError")),
                "same name in another registry is a different class");
    }

    @Test
    void stringMatchingResolvesInTheInstalledRegistry() {
        ExceptionTypeRegistry registry = ExceptionTypeRegistry.standard();
        PyException value = new PyException(registry.type("KeyError"), "k");
        ExceptionTypeRegistry previous = ExceptionTypeRegistry.install(registry);
        try {
            assertTrue(PyErrors.matches(value, "LookupError"));
            assertTrue(PyErrors.matches(value, "BaseException"));
            assertFalse(PyErrors.matches(value, "ValueError"));
        } finally {
            ExceptionTypeRegistry.restore(previous);
        }
    }

    @Test
    void javaMappingPicksTheTypeButKeepsTheThrowable() {
        ExceptionTypeRegistry registry = ExceptionTypeRegistry.standard();
        ExceptionTypeRegistry previous = ExceptionTypeRegistry.install(registry);
        try {
            NumberFormatException original = new NumberFormatException("bad number");
            PyException value = assertInstanceOf(PyException.class,
                    PyErrors.fromJava(original).value());

            assertSame(registry.type("ValueError"), value.type());
            assertSame(original, value.javaCause());
            assertTrue(PyErrors.matches(value, registry.type("Exception")));
            assertFalse(PyErrors.matches(value, registry.type("LookupError")));

            PyException unknown = assertInstanceOf(PyException.class,
                    PyErrors.fromJava(new IllegalStateException("boom")).value());
            assertSame(registry.type("JavaException"), unknown.type());
            assertEquals("java.lang.IllegalStateException: boom", unknown.message());
        } finally {
            ExceptionTypeRegistry.restore(previous);
        }
    }
}
