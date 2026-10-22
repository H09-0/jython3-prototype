package org.example.jython3;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Jython3Interpreter (Phase 5 – Functions & Call Stack).
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
 *   • Function definitions, calls, parameters, return values
 *   • Nested function calls
 *   • Local scope isolation
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

    private String out() { return outCapture.toString().trim(); }
    private String err() { return errCapture.toString().trim(); }

    // ═══════════════════════════════════════════════════════════════════
    //  1. print() with string literals
    // ═══════════════════════════════════════════════════════════════════
    @Test void testPrintString() {
        py.exec("print('Hello, World!')");
        assertEquals("Hello, World!", out());
    }

    @Test void testPrintDoubleQuoteString() {
        py.exec("print(\"Jython3\")");
        assertEquals("Jython3", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. Variable assignment & retrieval
    // ═══════════════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════════════
    //  3. Arithmetic
    // ═══════════════════════════════════════════════════════════════════
    @Test void testAddition() { py.exec("print(3 + 4)"); assertEquals("7", out()); }
    @Test void testSubtraction() { py.exec("print(10 - 3)"); assertEquals("7", out()); }
    @Test void testMultiplication() { py.exec("print(6 * 7)"); assertEquals("42", out()); }
    @Test void testDivision() { py.exec("print(20 / 4)"); assertEquals("5", out()); }

    @Test void testOperatorPrecedence_MulBeforeAdd() {
        py.exec("print(2 + 3 * 4)");
        assertEquals("14", out());
    }

    @Test void testParenthesesOverridePrecedence() {
        py.exec("print((2 + 3) * 4)");
        assertEquals("20", out());
    }

    @Test void testArithmeticWithVariable() {
        py.exec("x = 5");
        py.exec("print(x + 3)");
        assertEquals("8", out());
    }

    @Test void testComplexArithmetic() {
        py.exec("print(10 + 2 * 3 - 1)");
        assertEquals("15", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. if / else
    // ═══════════════════════════════════════════════════════════════════
    @Test void testIfTrue() { py.exec("if 1: print('yes')"); assertEquals("yes", out()); }
    @Test void testIfFalse() { py.exec("if 0: print('yes')"); assertEquals("", out()); }

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

    // ═══════════════════════════════════════════════════════════════════
    //  5. while loops
    // ═══════════════════════════════════════════════════════════════════
    @Test void testWhileFalseDoesNotExecute() {
        py.exec("while 0: print('loop')");
        assertEquals("", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Module import
    // ═══════════════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════════════
    //  7. Error handling
    // ═══════════════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════════════
    //  8. Bytecode generation
    // ═══════════════════════════════════════════════════════════════════
    @Test void testBytecodeNotNullOrEmpty() throws Jython3Interpreter.InterpreterException {
        int[] bc = py.compileToBytecode("print(1 + 2)");
        assertNotNull(bc);
        assertTrue(bc.length > 0);
    }

    @Test void testBytecodeLength_PrintInt() throws Jython3Interpreter.InterpreterException {
        int[] bc = py.compileToBytecode("print(5)");
        assertEquals(3, bc.length);
    }

    @Test void testBytecodeLength_Assign() throws Jython3Interpreter.InterpreterException {
        int[] bc = py.compileToBytecode("x = 10");
        assertEquals(3, bc.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. Function definitions & calls (Phase 5 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testDefineAndCallSimpleFunction() {
        // def greet(): print('hi')
        // greet()
        py.exec("def greet(): print('hi')");
        py.exec("greet()");
        assertEquals("hi", out());
    }

    @Test void testFunctionWithOneParam() {
        py.exec("def say(msg): print(msg)");
        py.exec("say('hello')");
        assertEquals("hello", out());
    }

    @Test void testFunctionWithTwoParams() {
        // def add(a, b): print(a + b)
        py.exec("def add(a, b): print(a + b)");
        py.exec("add(3, 4)");
        assertEquals("7", out());
    }

    @Test void testFunctionWithReturnValue() {
        // def double(x): return x * 2
        py.exec("def double(x): return x * 2");
        py.exec("print(double(5))");
        assertEquals("10", out());
    }

    @Test void testFunctionReturnInExpression() {
        // def square(n): return n * n
        py.exec("def square(n): return n * n");
        py.exec("print(square(4) + 1)");
        assertEquals("17", out());
    }

    @Test void testFunctionCallAsArgument() {
        // def inc(x): return x + 1
        py.exec("def inc(x): return x + 1");
        py.exec("print(inc(inc(3)))");
        assertEquals("5", out());
    }

    @Test void testFunctionLocalScopeIsolation() {
        // x at global level should not be changed by local x inside function
        py.exec("x = 100");
        py.exec("def f(x): print(x)");
        py.exec("f(42)");
        py.exec("print(x)");
        assertEquals("42" + System.lineSeparator() + "100", outCapture.toString().trim());
    }

    @Test void testFunctionMultipleStatements() {
        // def calc(a, b): print(a + b); print(a * b)
        py.exec("def calc(a, b): print(a + b); print(a * b)");
        py.exec("calc(3, 5)");
        assertEquals("8" + System.lineSeparator() + "15", outCapture.toString().trim());
    }

    @Test void testFunctionReadsGlobalVariable() {
        // Function can read global variables
        py.exec("g = 10");
        py.exec("def show(): print(g)");
        py.exec("show()");
        assertEquals("10", out());
    }

    @Test void testStandaloneCallDoesNotLeakReturnValue() {
        // Standalone call discards return value
        py.exec("def noop(): return 42");
        py.exec("noop()");
        assertEquals("", out());  // nothing printed
    }

    @Test void testUncallableError() {
        py.exec("x = 5");
        py.exec("x()");
        assertTrue(err().contains("TypeError") || err().contains("not callable"));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. List literals (Phase 6 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testListLiteralPrint() {
        py.exec("print([1, 2, 3])");
        assertEquals("[1, 2, 3]", out());
    }

    @Test void testListAssignAndPrint() {
        py.exec("xs = [10, 20, 30]");
        py.exec("print(xs)");
        assertEquals("[10, 20, 30]", out());
    }

    @Test void testEmptyList() {
        py.exec("print([])");
        assertEquals("[]", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. Subscript access (Phase 6 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testSubscriptAccess() {
        py.exec("xs = [10, 20, 30]");
        py.exec("print(xs[1])");
        assertEquals("20", out());
    }

    @Test void testSubscriptFirstElement() {
        py.exec("xs = [5, 6, 7]");
        py.exec("print(xs[0])");
        assertEquals("5", out());
    }

    @Test void testSubscriptOutOfRange() {
        py.exec("xs = [1, 2]");
        py.exec("print(xs[5])");
        assertTrue(err().contains("IndexError"));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  12. for ... in ... loops (Phase 6 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testForLoopOverList() {
        py.exec("xs = [1, 2, 3]");
        py.exec("for x in xs: print(x)");
        assertEquals("1" + System.lineSeparator() + "2" + System.lineSeparator() + "3",
                     outCapture.toString().trim());
    }

    @Test void testForLoopOverEmptyList() {
        py.exec("for x in []: print(x)");
        assertEquals("", out());
    }

    @Test void testForLoopWithArithmetic() {
        py.exec("for n in [10, 20]: print(n + 1)");
        assertEquals("11" + System.lineSeparator() + "21", outCapture.toString().trim());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  13. CompiledCode & bytecode layer (Phase 6 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testCompiledCodeHasConstPool() {
        var code = py.compileToCode("print(42)");
        assertNotNull(code);
        assertNotNull(code.constPool());
        assertTrue(code.constPool().length > 0);
    }

    @Test void testCompiledCodeBytecodeLength() {
        var code = py.compileToCode("x = 5");
        // x = 5 → LOAD_CONST, STORE_FAST, HALT = 3
        assertEquals(3, code.bytecode().length);
    }

    @Test void testBytecodeEncoding() {
        var code = py.compileToCode("print(1)");
        int first = code.bytecode()[0];
        // first instruction should be LOAD_CONST (ordinal 0) → high 16 bits = 0
        assertEquals(0, first >>> 16);
    }
}
