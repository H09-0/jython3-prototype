package org.example.jython3;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Jython3Interpreter (Phase 4 – ANTLR4 parser).
 *
 * Covers:
 *   • print() with string literals
 *   • Variable assignment + retrieval
 *   • Arithmetic: +, -, *, / and operator precedence
 *   • if / else control flow
 *   • while loops
 *   • Module import
 *   • Error handling (NameError, ZeroDivisionError, SyntaxError)
 *   • Bytecode generation
 */
class Jython3InterpreterTest {

    private Jython3Interpreter py;
    private ByteArrayOutputStream outCapture;
    private ByteArrayOutputStream errCapture;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        py = new Jython3Interpreter();
        outCapture = new ByteArrayOutputStream();
        errCapture = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outCapture));
        System.setErr(new PrintStream(errCapture));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // ─── Utility ──────────────────────────────────────────────────────
    private String out() { return outCapture.toString().trim(); }
    private String err() { return errCapture.toString().trim(); }

    // ─── 1. print() with string literals ────────────────────────────
    @Test void testPrintString() {
        py.exec("print('Hello, World!')");
        assertEquals("Hello, World!", out());
    }

    @Test void testPrintDoubleQuoteString() {
        py.exec("print(\"Jython3\")");
        assertEquals("Jython3", out());
    }

    // ─── 2. Variable assignment & retrieval ─────────────────────────
    @Test void testVariableAssignAndPrint() {
        py.exec("x = 42");
        py.exec("print(x)");
        assertEquals("42", out());
    }

    @Test void testMultipleVariables() {
        py.exec("a = 10");
        py.exec("b = 20");
        py.exec("print(a)");
        py.exec("print(b)");
        assertEquals("10" + System.lineSeparator() + "20", outCapture.toString().trim());
    }

    // ─── 3. Arithmetic ───────────────────────────────────────────────
    @Test void testAddition() {
        py.exec("print(3 + 4)");
        assertEquals("7", out());
    }

    @Test void testSubtraction() {
        py.exec("print(10 - 3)");
        assertEquals("7", out());
    }

    @Test void testMultiplication() {
        py.exec("print(6 * 7)");
        assertEquals("42", out());
    }

    @Test void testDivision() {
        py.exec("print(20 / 4)");
        assertEquals("5", out());
    }

    @Test void testOperatorPrecedence_MulBeforeAdd() {
        // 2 + 3 * 4 = 14  (not 20)
        py.exec("print(2 + 3 * 4)");
        assertEquals("14", out());
    }

    @Test void testParenthesesOverridePrecedence() {
        // (2 + 3) * 4 = 20
        py.exec("print((2 + 3) * 4)");
        assertEquals("20", out());
    }

    @Test void testArithmeticWithVariable() {
        py.exec("x = 5");
        py.exec("print(x + 3)");
        assertEquals("8", out());
    }

    @Test void testComplexArithmetic() {
        // 10 + 2 * 3 - 1 = 15
        py.exec("print(10 + 2 * 3 - 1)");
        assertEquals("15", out());
    }

    // ─── 4. if / else ────────────────────────────────────────────────
    @Test void testIfTrue() {
        py.exec("if 1: print('yes')");
        assertEquals("yes", out());
    }

    @Test void testIfFalse() {
        py.exec("if 0: print('yes')");
        assertEquals("", out());
    }

    @Test void testIfElseTrueBranch() {
        py.exec("if 1: print('yes') else: print('no')");
        assertEquals("yes", out());
    }

    @Test void testIfElseFalseBranch() {
        py.exec("if 0: print('yes') else: print('no')");
        assertEquals("no", out());
    }

    @Test void testIfWithVariableCondition() {
        py.exec("x = 5");
        py.exec("if x: print('truthy') else: print('falsy')");
        assertEquals("truthy", out());
    }

    // ─── 5. while loops ──────────────────────────────────────────────
    @Test void testWhileFalseDoesNotExecute() {
        py.exec("while 0: print('loop')");
        assertEquals("", out());
    }

    // ─── 6. Module import ────────────────────────────────────────────
    @Test void testImportModule() throws IOException {
        Files.createDirectories(Path.of("modules"));
        Files.writeString(Path.of("modules/testmod.py"), "a = 99\n");
        py.exec("import testmod");
        py.exec("print(a)");
        assertEquals("99", out());
    }

    @Test void testImportNonExistentModule() {
        py.exec("import nonexistent_xyz");
        assertTrue(err().contains("ImportError"));
    }

    // ─── 7. Error handling ───────────────────────────────────────────
    @Test void testNameError() {
        py.exec("print(undefined_var)");
        assertTrue(err().contains("NameError"));
    }

    @Test void testZeroDivisionError() {
        py.exec("print(10 / 0)");
        assertTrue(err().contains("ZeroDivisionError"));
    }

    @Test void testSyntaxError() {
        py.exec("@@@invalid@@@");
        String combined = out() + err();
        assertTrue(combined.contains("SyntaxError") || combined.contains("Traceback"));
    }

    // ─── 8. Bytecode generation ──────────────────────────────────────
    @Test void testBytecodeNotNullOrEmpty() throws Jython3Interpreter.InterpreterException {
        int[] bc = py.compileToBytecode("print(1 + 2)");
        assertNotNull(bc);
        assertTrue(bc.length > 0);
    }

    @Test void testBytecodeLength_PrintInt() throws Jython3Interpreter.InterpreterException {
        // print(5) → LOAD_CONST, PRINT, HALT = 3 opcodes
        int[] bc = py.compileToBytecode("print(5)");
        assertEquals(3, bc.length);
    }

    @Test void testBytecodeLength_Assign() throws Jython3Interpreter.InterpreterException {
        // x = 10 → LOAD_CONST, STORE_FAST, HALT = 3 opcodes
        int[] bc = py.compileToBytecode("x = 10");
        assertEquals(3, bc.length);
    }
}
