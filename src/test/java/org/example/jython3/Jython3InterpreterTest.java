package org.example.jython3;

import org.junit.jupiter.api.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic sanity‑check tests for the prototype interpreter.
 *
 * • {@code testPrintHelloWorld} verifies that a simple {@code print('…')} statement
 *   writes the expected text to {@code System.out}.
 * • {@code testUnsupportedSyntax} ensures that the interpreter throws an
 *   {@link IllegalArgumentException} for code it does not understand (e.g.
 *   variable assignment).
 */
class Jython3InterpreterTest {
    private final ByteArrayOutputStream outCapture = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        System.setOut(new PrintStream(outCapture));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void testPrintHelloWorld() {
        Jython3Interpreter py = new Jython3Interpreter();
        py.exec("print('Hello, world!')");
        assertEquals("Hello, world!", outCapture.toString().trim());
    }

    @Test
    void testUnsupportedSyntax() {
        Jython3Interpreter py = new Jython3Interpreter();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                py.exec("x = 5")
        );
        assertTrue(ex.getMessage().contains("Unsupported syntax"));
    }
}
