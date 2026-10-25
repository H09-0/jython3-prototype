package org.example.jython3;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyException;
import org.example.jython3.runtime.PyObject;

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
    @Test void testBytecodeNotNullOrEmpty() throws InterpreterException {
        int[] bc = py.compileToBytecode("print(1 + 2)");
        assertNotNull(bc);
        assertTrue(bc.length > 0);
    }

    @Test void testBytecodeLength_PrintInt() throws InterpreterException {
        int[] bc = py.compileToBytecode("print(5)");
        assertEquals(3, bc.length);
    }

    @Test void testBytecodeLength_Assign() throws InterpreterException {
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

    // ═══════════════════════════════════════════════════════════════════
    //  14. math module (Phase 7 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testMathSqrt() {
        py.exec("import math");
        py.exec("print(math.sqrt(4))");
        assertEquals("2.0", out());
    }

    @Test void testMathSqrt9() {
        py.exec("import math");
        py.exec("print(math.sqrt(9))");
        assertEquals("3.0", out());
    }

    @Test void testMathFloor() {
        py.exec("import math");
        py.exec("print(math.floor(3))");
        assertEquals("3", out());
    }

    @Test void testMathCeil() {
        py.exec("import math");
        py.exec("print(math.ceil(3))");
        assertEquals("3", out());
    }

    @Test void testMathPiAttr() {
        py.exec("import math");
        py.exec("x = math.pi");
        // x must be stored as PyFloat close to Math.PI
        // just verify no error and something was printed
        py.exec("print(math.pi)");
        assertTrue(out().startsWith("3."));
    }

    @Test void testMathEAttr() {
        py.exec("import math");
        py.exec("print(math.e)");
        assertTrue(out().startsWith("2."));
    }

    @Test void testMathModuleType() {
        py.exec("import math");
        // math should be importable without error
        assertEquals("", err());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  15. sys module (Phase 7 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testSysArgvIsEmpty() {
        py.exec("import sys");
        py.exec("print(sys.argv)");
        assertEquals("[]", out());
    }

    @Test void testSysVersion() {
        py.exec("import sys");
        py.exec("print(sys.version)");
        assertTrue(out().contains("3"));
    }

    @Test void testSysPlatform() {
        py.exec("import sys");
        py.exec("print(sys.platform)");
        assertEquals("java", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  16. os module (Phase 7 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testOsGetcwd() {
        py.exec("import os");
        py.exec("print(os.getcwd())");
        // Should print the working directory (non-empty string, no error)
        assertFalse(out().isBlank());
        assertEquals("", err());
    }

    @Test void testOsSep() {
        py.exec("import os");
        py.exec("print(os.sep)");
        // On Linux this is "/"
        assertFalse(out().isBlank());
    }

    @Test void testOsName() {
        py.exec("import os");
        py.exec("print(os.name)");
        assertEquals("java", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  17. Attribute-access error handling (Phase 7 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testModuleAttributeError() {
        py.exec("import math");
        py.exec("print(math.nonexistent)");
        assertTrue(err().contains("AttributeError"));
    }

    @Test void testImportUnknownModule() {
        py.exec("import totally_unknown_xyz");
        assertTrue(err().contains("ImportError"));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  18. Exception handling – try / except / raise  (Phase 8 – NEW)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testTryNormalPath() {
        // No exception raised – except block must NOT run
        py.exec("try: print('ok') except: print('err')");
        assertEquals("ok", out());
        assertEquals("", err());
    }

    @Test void testTryCatchesZeroDivision() {
        py.exec("try: print(1 / 0) except: print('caught')");
        assertEquals("caught", out());
        assertEquals("", err());
    }

    @Test void testTryCatchesNameError() {
        py.exec("try: print(undefined_var) except: print('caught')");
        assertEquals("caught", out());
    }

    @Test void testRaiseIsCaught() {
        py.exec("try: raise ValueError('oops') except: print('handled')");
        assertEquals("handled", out());
    }

    @Test void testRaiseUncaught() {
        // raise outside try – should produce traceback
        py.exec("raise RuntimeError('boom')");
        assertTrue(err().contains("RuntimeError") || err().contains("Traceback"));
    }

    @Test void testRaiseBareExcType() {
        // raise with no message
        py.exec("try: raise StopIteration except: print('stopped')");
        assertEquals("stopped", out());
    }

    @Test void testTryDoesNotSuppressOtherOutput() {
        // code before try runs normally
        py.exec("x = 5");
        py.exec("try: print(x) except: print('err')");
        assertEquals("5", out());
    }

    @Test void testTryWithTypedExcept() {
        // try: ... except ExcType: ... syntax (typed except clause)
        py.exec("try: raise ValueError('bad') except ValueError: print('type caught')");
        assertEquals("type caught", out());
    }

    @Test void testExceptionInFunctionCaughtByCallerTry() {
        // Exception raised inside a function propagates to caller’s try block
        py.exec("def boom(): raise RuntimeError('fn err')");
        py.exec("try: boom() except: print('caller caught')");
        assertEquals("caller caught", out());
    }

    @Test void testRaiseWithIntMessage() {
        // raise with integer expr as message
        py.exec("try: raise ValueError(42) except: print('caught')");
        assertEquals("caught", out());
    }

    @Test void testExceptExceptionDoesNotCatchSystemExit() {
        py.exec("""
            try:
                raise SystemExit
            except Exception:
                print("wrong")
            """);
        assertFalse(out().contains("wrong"), "Exception must not catch SystemExit");
    }

    @Test void testExceptBaseExceptionCatchesSystemExit() {
        py.exec("""
            try:
                raise SystemExit
            except BaseException:
                print("caught")
            """);
        assertEquals("caught", out());
        assertEquals("", err());
    }

    @Test void testExceptExceptionCatchesSecurityError() {
        py.exec("""
            try:
                import sun.misc.Unsafe
            except Exception:
                print("blocked")
            """);
        assertEquals("blocked", out());
    }

    @Test void testJavaErrorsMapToPythonTypes() {
        py.exec("""
            from java.lang import Integer
            try:
                Integer.parseInt("abc")
            except ValueError:
                print("value error")
            """);
        assertEquals("", err());
        assertEquals("value error", out());
    }

    @Test void testUnknownJavaErrorsAreJavaException() {
        py.exec("""
            from java.lang import String
            s = String("abc")
            try:
                s.getBytes("no-such-encoding")
            except JavaException:
                print("java exception")
            """);
        assertEquals("", err());
        assertEquals("java exception", out());
    }

    @Test void testExceptionValueKeepsTracebackAfterHandlerExits() {
        py.exec("""
            def inner():
                raise ValueError("boom")
            try:
                inner()
            except ValueError as e:
                saved = e
            """);
        PyObject saved = py.getGlobal("saved");
        assertInstanceOf(PyException.class, saved);
        var frames = ((PyException) saved).traceback();
        assertFalse(frames.isEmpty(), "the value must keep its frames after unwinding");
        assertTrue(frames.size() >= 2, "expected the inner and module frames, got " + frames);
    }

    @Test void testExceptionValueUsesTheInterpretersTypeObjects() {
        py.exec("""
            try:
                raise ValueError("boom")
            except ValueError as e:
                saved = e
            """);
        PyException saved = assertInstanceOf(PyException.class, py.getGlobal("saved"));
        assertSame(py.exceptionTypes().type("ValueError"), saved.type());
        assertEquals("ValueError", saved.typeName());
    }

    @Test void testHandlerMatchingWalksTheRealHierarchy() {
        py.exec("""
            try:
                raise KeyError("k")
            except LookupError:
                caught = "lookup"
            """);
        assertEquals("lookup", py.getGlobal("caught").toString());
    }

    @Test void testExceptionHandlerSkipsBaseExceptionOnlyTypes() {
        py.exec("""
            try:
                raise KeyboardInterrupt("stop")
            except Exception:
                caught = "wrong"
            except BaseException:
                caught = "base"
            """);
        assertEquals("base", py.getGlobal("caught").toString());
    }

    @Test void testEachInterpreterOwnsItsExceptionTypes() {
        py.exec("""
            try:
                raise ValueError("first")
            except ValueError as e:
                saved = e
            """);
        Jython3Interpreter other = new Jython3Interpreter();
        other.exec("""
            try:
                raise ValueError("second")
            except ValueError as e:
                saved = e
            """);

        PyException first = assertInstanceOf(PyException.class, py.getGlobal("saved"));
        PyException second = assertInstanceOf(PyException.class, other.getGlobal("saved"));
        assertEquals(first.typeName(), second.typeName());
        assertNotSame(first.type(), second.type(),
                "each interpreter must use its own exception classes");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  19. Phase 9: Jython 2.7 Replacement Foundations
    // ═══════════════════════════════════════════════════════════════════

    // ── Comparisons & Booleans ──
    @Test void testBooleansAndNone() {
        py.exec("print(True)");
        py.exec("print(False)");
        py.exec("print(None)");
        assertEquals("True" + System.lineSeparator() + "False" + System.lineSeparator() + "None", outCapture.toString().trim());
    }

    @Test void testComparisons() {
        py.exec("print(10 == 10)");
        py.exec("print(10 != 20)");
        py.exec("print(5 < 10)");
        py.exec("print(10 <= 10)");
        py.exec("print(15 > 5)");
        py.exec("print(5 >= 5)");
        String expected = String.join(System.lineSeparator(), "True", "True", "True", "True", "True", "True");
        assertEquals(expected, outCapture.toString().trim());
    }

    @Test void testLogicalOperators() {
        py.exec("print(True and True)");
        py.exec("print(True and False)");
        py.exec("print(False or True)");
        py.exec("print(not False)");
        String expected = String.join(System.lineSeparator(), "True", "False", "True", "True");
        assertEquals(expected, outCapture.toString().trim());
    }

    @Test void testUnaryMinusAndPower() {
        py.exec("x = -5");
        py.exec("print(-x)");
        py.exec("print(2 ** 3)");
        assertEquals("5" + System.lineSeparator() + "8", outCapture.toString().trim());
    }

    @Test void testModuloAndFloorDivision() {
        py.exec("print(17 % 5)");
        py.exec("print(17 // 5)");
        assertEquals("2" + System.lineSeparator() + "3", outCapture.toString().trim());
    }

    @Test void testAugmentedAssignment() {
        py.exec("x = 10");
        py.exec("x += 5");
        py.exec("x *= 2");
        py.exec("x -= 4");
        py.exec("x //= 2");
        py.exec("print(x)");
        assertEquals("13", out());
    }

    @Test void testInAndNotIn() {
        py.exec("print(2 in [1, 2, 3])");
        py.exec("print(4 not in [1, 2, 3])");
        py.exec("print('py' in 'python')");
        String expected = String.join(System.lineSeparator(), "True", "True", "True");
        assertEquals(expected, outCapture.toString().trim());
    }

    // ── Dictionaries ──
    @Test void testDictLiteralAndAccess() {
        py.exec("d = {'name': 'Jython3', 'ver': 3}");
        py.exec("print(d['name'])");
        py.exec("print(d['ver'])");
        assertEquals("Jython3" + System.lineSeparator() + "3", outCapture.toString().trim());
    }

    @Test void testDictAssignAndMethods() {
        py.exec("d = {'a': 1}");
        py.exec("d['b'] = 2");
        py.exec("print(d.get('b'))");
        py.exec("print(d.get('missing', 99))");
        assertEquals("2" + System.lineSeparator() + "99", outCapture.toString().trim());
    }

    // ── Strings & Lists Methods ──
    @Test void testStringMethodsAndConcat() {
        py.exec("s = 'hello' + ' ' + 'world'");
        py.exec("print(s.upper())");
        py.exec("print(s.startswith('hello'))");
        py.exec("print(s.replace('world', 'jython'))");
        String expected = String.join(System.lineSeparator(), "HELLO WORLD", "True", "hello jython");
        assertEquals(expected, outCapture.toString().trim());
    }

    @Test void testListMethodsAndAssignment() {
        py.exec("nums = [3, 1, 2]");
        py.exec("nums.append(4)");
        py.exec("nums.sort()");
        py.exec("print(nums)");
        py.exec("nums[0] = 99");
        py.exec("print(nums[0])");
        assertEquals("[1, 2, 3, 4]" + System.lineSeparator() + "99", outCapture.toString().trim());
    }

    // ── Built-in Functions ──
    @Test void testBuiltinFunctions() {
        py.exec("print(len([10, 20, 30]))");
        py.exec("print(sum([1, 2, 3, 4]))");
        py.exec("print(min(15, 3, 20))");
        py.exec("print(max(15, 3, 20))");
        py.exec("print(abs(-42))");
        py.exec("print(int('123') + 1)");
        py.exec("print(str(100) + ' items')");
        String expected = String.join(System.lineSeparator(), "3", "10", "3", "20", "42", "124", "100 items");
        assertEquals(expected, outCapture.toString().trim());
    }

    @Test void testRangeAndEnumerate() {
        py.exec("r = range(3)");
        py.exec("print(r)");
        py.exec("print(list(range(3)))");
        assertTrue(outCapture.toString().contains("[0, 1, 2]"));
    }

    // ── Elif & Multi-statement & Comments ──
    @Test void testIfElifElse() {
        py.exec("x = 0; if x > 0: print('pos') elif x == 0: print('zero') else: print('neg')");
        assertEquals("zero", out());
    }

    @Test void testCommentsAndMultiLineScript() {
        String script = """
            # Initialization
            a = 10
            # Next variable
            b = 20
            # Calculation
            res = a + b
            print(res)
            """;
        py.exec(script);
        assertEquals("30", out());
    }

    @Test void testMultiArgPrint() {
        py.exec("print('Value:', 42, True)");
        assertEquals("Value: 42 True", out());
    }

    @Test void testDelStatement() {
        py.exec("x = 55; del x; try: print(x) except NameError: print('deleted')");
        assertEquals("deleted", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  20. Phase 10: Indentation Blocks & Object-Oriented Programming
    // ═══════════════════════════════════════════════════════════════════

    @Test void testMultiLineIndentedFunction() {
        String script = """
            def compute(x):
                total = 0
                for n in [1, 2, 3]:
                    total += n * x
                return total

            print(compute(10))
            """;
        py.exec(script);
        assertEquals("60", out());
    }

    @Test void testNestedIfInIndentedBlock() {
        String script = """
            def classify(val):
                if val > 0:
                    if val > 100:
                        return 'huge'
                    else:
                        return 'positive'
                else:
                    return 'negative or zero'

            print(classify(150))
            print(classify(50))
            print(classify(-5))
            """;
        py.exec(script);
        String expected = String.join(System.lineSeparator(), "huge", "positive", "negative or zero");
        assertEquals(expected, outCapture.toString().trim());
    }

    @Test void testSimpleClassAndInstance() {
        String script = """
            class Person:
                def __init__(self, name, age):
                    self.name = name
                    self.age = age

                def describe(self):
                    return self.name + " is " + str(self.age)

            p = Person("Alice", 25)
            print(p.name)
            print(p.describe())
            """;
        py.exec(script);
        assertEquals("Alice" + System.lineSeparator() + "Alice is 25", outCapture.toString().trim());
    }

    @Test void testClassInheritance() {
        String script = """
            class Animal:
                def __init__(self, kind):
                    self.kind = kind

                def speak(self):
                    return "Some sound"

            class Dog(Animal):
                def speak(self):
                    return "Woof"

            d = Dog("Canine")
            print(d.kind)
            print(d.speak())
            print(isinstance(d, Dog))
            print(isinstance(d, Animal))
            """;
        py.exec(script);
        String expected = String.join(System.lineSeparator(), "Canine", "Woof", "True", "True");
        assertEquals(expected, outCapture.toString().trim());
    }

    @Test void testInstanceDynamicAttributes() {
        String script = """
            class Box:
                pass

            b = Box()
            b.width = 10
            b.height = 20
            print(b.width * b.height)
            """;
        py.exec(script);
        assertEquals("200", out());
    }

    @Test void testImplicitLineContinuationInBrackets() {
        String script = """
            matrix = [
                10,
                20,
                30
            ]
            print(sum(matrix))
            """;
        py.exec(script);
        assertEquals("60", out());
    }

    // ── Vulnerability & Out-of-the-Box Edge Cases ──

    @Test void testBadUnindentError() {
        String script = """
            def bad():
                x = 10
              y = 20
            """;
        py.exec(script);
        assertTrue(err().contains("IndentationError") || err().contains("unindent does not match"));
    }

    @Test void testUnexpectedIndentAtLine1() {
        String script = "    x = 10\n";
        py.exec(script);
        assertTrue(err().contains("IndentationError") || err().contains("unexpected indent"));
    }

    @Test void testIndentationBombProtection() {
        // Generate an indentation deeper than MAX_INDENT_DEPTH (100)
        var sb = new StringBuilder();
        for (int i = 0; i < 110; i++) {
            sb.append(" ".repeat(i * 4)).append("if True:\n");
        }
        sb.append(" ".repeat(110 * 4)).append("print('bomb')\n");
        py.exec(sb.toString());
        assertTrue(err().contains("IndentationError") && err().contains("maximum indentation depth exceeded"));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  JAVA BRIDGE TESTS
    // ═══════════════════════════════════════════════════════════════════

    // ── JB-1: Import and use static methods ──────────────────────────
    @Test void testJavaBridgeStaticMethod() {
        py.exec("""
            from java.lang import Math
            x = Math.sqrt(144.0)
            print(x)
            """);
        assertEquals("", err());
        assertEquals("12.0", out());
    }

    @Test void testJavaBridgeStaticMethodMax() {
        py.exec("""
            from java.lang import Math
            x = Math.max(7, 42)
            print(x)
            """);
        assertEquals("", err());
        assertEquals("42", out());
    }

    @Test void testJavaBridgeStaticField() {
        py.exec("""
            from java.lang import Integer
            print(Integer.MAX_VALUE)
            """);
        assertEquals("", err());
        assertEquals("2147483647", out());
    }

    @Test void testJavaBridgePiConstant() {
        py.exec("""
            from java.lang import Math
            print(Math.PI)
            """);
        assertEquals("", err());
        String result = out();
        assertTrue(result.startsWith("3.14"), "Expected Pi, got: " + result);
    }

    // ── JB-2: Instantiate Java objects ──────────────────────────────
    @Test void testJavaBridgeStringBuilder() {
        py.exec("""
            from java.lang import StringBuilder
            sb = StringBuilder()
            sb.append("hello")
            sb.append(" world")
            print(sb.toString())
            """);
        assertEquals("", err());
        assertEquals("hello world", out());
    }

    @Test void testExecfile() throws java.io.IOException {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("test_execfile", ".py");
        try {
            java.nio.file.Files.writeString(tmp, "val = 42\nprint('from execfile:', val)");
            py.exec("execfile('" + tmp.toAbsolutePath().toString().replace("\\", "/") + "')");
            assertEquals("", err());
            assertEquals("from execfile: 42", out());
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test void testJavaBridgeArrayList() {
        py.exec("""
            from java.util import ArrayList
            lst = ArrayList()
            lst.add("alpha")
            lst.add("beta")
            lst.add("gamma")
            print(lst.size())
            """);
        assertEquals("", err());
        assertEquals("3", out());
    }

    @Test void testJavaBridgeArrayListGet() {
        py.exec("""
            from java.util import ArrayList
            lst = ArrayList()
            lst.add("first")
            lst.add("second")
            print(lst.get(0))
            print(lst.get(1))
            """);
        assertEquals("", err());
        assertEquals("first\nsecond", out());
    }

    @Test void testJavaBridgeArrayListSubscript() {
        py.exec("""
            from java.util import ArrayList
            lst = ArrayList()
            lst.add("A")
            lst.add("B")
            lst.add("C")
            print(lst[0])
            print(lst[2])
            """);
        assertEquals("", err());
        assertEquals("A\nC", out());
    }

    @Test void testJavaBridgeHashMap() {
        py.exec("""
            from java.util import HashMap
            m = HashMap()
            m.put("key", "value")
            print(m.get("key"))
            print(m.size())
            """);
        assertEquals("", err());
        assertEquals("value\n1", out());
    }

    @Test void testJavaBridgeHashMapSubscript() {
        py.exec("""
            from java.util import HashMap
            m = HashMap()
            m.put("greeting", "hello")
            print(m["greeting"])
            """);
        assertEquals("", err());
        assertEquals("hello", out());
    }

    // ── JB-3: len() on Java collections ─────────────────────────────
    @Test void testJavaBridgeLenOnList() {
        py.exec("""
            from java.util import ArrayList
            lst = ArrayList()
            lst.add(1)
            lst.add(2)
            lst.add(3)
            print(len(lst))
            """);
        assertEquals("", err());
        assertEquals("3", out());
    }

    @Test void testJavaBridgeLenOnMap() {
        py.exec("""
            from java.util import HashMap
            m = HashMap()
            m.put("a", 1)
            m.put("b", 2)
            print(len(m))
            """);
        assertEquals("", err());
        assertEquals("2", out());
    }

    // ── JB-4: Iterate over Java collections ─────────────────────────
    @Test void testJavaBridgeIterateArrayList() {
        py.exec("""
            from java.util import ArrayList
            lst = ArrayList()
            lst.add("x")
            lst.add("y")
            lst.add("z")
            for item in lst:
                print(item)
            """);
        assertEquals("", err());
        assertEquals("x\ny\nz", out());
    }

    // ── JB-5: Java string operations ─────────────────────────────────
    @Test void testJavaBridgeStringMethods() {
        py.exec("""
            from java.lang import String
            s = String("Hello World")
            print(s.toLowerCase())
            print(s.length())
            """);
        assertEquals("", err());
        assertEquals("hello world\n11", out());
    }

    // ── JB-6: Java Math operations ───────────────────────────────────
    @Test void testJavaBridgeAbsAndFloor() {
        py.exec("""
            from java.lang import Math
            print(Math.abs(-42))
            print(Math.floor(3.7))
            """);
        assertEquals("", err());
        assertEquals("42\n3.0", out());
    }

    @Test void testJavaBridgePow() {
        py.exec("""
            from java.lang import Math
            print(Math.pow(2.0, 10.0))
            """);
        assertEquals("", err());
        assertEquals("1024.0", out());
    }

    // ── JB-7: Import with alias ──────────────────────────────────────
    @Test void testJavaBridgeImportAlias() {
        py.exec("""
            import java.util.ArrayList as AL
            lst = AL()
            lst.add("item1")
            print(lst.size())
            """);
        assertEquals("", err());
        assertEquals("1", out());
    }

    // ── JB-8: Exception handling for Java exceptions ─────────────────
    @Test void testJavaBridgeExceptionHandling() {
        py.exec("""
            from java.util import ArrayList
            lst = ArrayList()
            try:
                x = lst.get(5)
            except Exception:
                print("caught")
            """);
        assertEquals("", err());
        assertEquals("caught", out());
    }

    // ── JB-9: Security - restricted classes ──────────────────────────
    @Test void testJavaBridgeSecurityUnsafeBlocked() {
        py.exec("import sun.misc.Unsafe");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityInternalBlocked() {
        py.exec("import jdk.internal.misc.Unsafe");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityMethodHandlesBlocked() {
        py.exec("import java.lang.invoke.MethodHandles");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityThreadBlocked() {
        py.exec("from java.lang import Thread");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityThreadGroupBlocked() {
        py.exec("from java.lang import ThreadGroup");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityObjectInputStreamBlocked() {
        py.exec("from java.io import ObjectInputStream");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityURLClassLoaderBlocked() {
        py.exec("from java.net import URLClassLoader");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityDesktopBlocked() {
        py.exec("from java.awt import Desktop");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    @Test void testJavaBridgeSecurityInnerClassOfForbiddenBlocked() {
        py.exec("from java.lang import Thread");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
        py.exec("from java.lang import Thread$State");
        assertTrue(err().contains("SecurityError") || err().contains("ImportError"),
                   "Expected security error, got: " + err());
    }

    // ── JB-10: Chained method calls ──────────────────────────────────
    @Test void testJavaBridgeChainedCalls() {
        py.exec("""
            from java.lang import StringBuilder
            sb = StringBuilder()
            sb.append("Jython")
            sb.append(" 3")
            sb.append(" rocks")
            result = sb.toString()
            print(result)
            """);
        assertEquals("", err());
        assertEquals("Jython 3 rocks", out());
    }

    // ── JB-11: typeof / typeName ─────────────────────────────────────
    @Test void testJavaBridgeTypeRepresentation() {
        py.exec("""
            from java.util import ArrayList
            lst = ArrayList()
            print(type(lst))
            """);
        assertEquals("", err());
        String result = out();
        assertTrue(result.contains("JavaObject"), "Expected JavaObject type, got: " + result);
    }

    // ── JB-12: Performance – cached reflection speed ──────────────────
    @Test void testJavaBridgeReflectionCachePerformance() {
        // Call same method 1000 times – should be fast due to caching
        py.exec("""
            from java.lang import Math
            i = 0
            while i < 1000:
                x = Math.abs(i)
                i = i + 1
            print(x)
            """);
        assertEquals("", err());
        assertEquals("999", out());
    }

    // ── JB-13: String conversion ─────────────────────────────────────
    @Test void testJavaBridgeToStringConversion() {
        py.exec("""
            from java.lang import Integer
            n = Integer.valueOf(42)
            print(n)
            """);
        assertEquals("", err());
        assertEquals("42", out());
    }

    // ── JB-14: Nested Java package navigation ────────────────────────
    @Test void testJavaBridgePackageNavigation() {
        py.exec("""
            import java.lang.Math as JMath
            print(JMath.sqrt(9.0))
            """);
        assertEquals("", err());
        assertEquals("3.0", out());
    }

    // ── JB-15: Boolean conversion from Java ──────────────────────────
    @Test void testJavaBridgeBooleanReturn() {
        py.exec("""
            from java.lang import String
            s = String("hello")
            print(s.isEmpty())
            """);
        assertEquals("", err());
        assertEquals("False", out());
    }

    @Test void testJavaBridgeBooleanReturnEmpty() {
        py.exec("""
            from java.lang import String
            s = String("")
            print(s.isEmpty())
            """);
        assertEquals("", err());
        assertEquals("True", out());
    }

    // ── DEC-01: Simple function decorator ────────────────────────────
    @Test void testSimpleFunctionDecorator() {
        py.exec("""
            def dec(f):
                def wrapper():
                    return f() + 1
                return wrapper
            
            @dec
            def foo():
                return 10
            
            print(foo())
            """);
        assertEquals("", err());
        assertEquals("11", out());
    }

    // ── DEC-02: Decorator with arguments ─────────────────────────────
    @Test void testDecoratorReturningFunction() {
        py.exec("""
            def bold(f):
                def inner():
                    return "***" + f() + "***"
                return inner
            
            @bold
            def greet():
                return "hi"
            
            print(greet())
            """);
        assertEquals("", err());
        assertEquals("***hi***", out());
    }

    // ── DEC-03: Multiple decorators ──────────────────────────────────
    @Test void testMultipleDecorators() {
        py.exec("""
            def add_one(f):
                def wrapper():
                    return f() + 1
                return wrapper
            
            def double(f):
                def wrapper():
                    return f() * 2
                return wrapper
            
            @add_one
            @double
            def num():
                return 5
            
            print(num())
            """);
        assertEquals("", err());
        assertEquals("11", out());
    }

    // ── DEC-04: Decorator preserving function behavior ──────────────
    @Test void testDecoratorWithLogic() {
        py.exec("""
            def logger(f):
                def wrapped(x):
                    return f(x) * 2
                return wrapped
            
            @logger
            def square(x):
                return x * x
            
            print(square(3))
            """);
        assertEquals("", err());
        assertEquals("18", out());
    }

    // ── DEC-05: Decorator applied to function returning value ───────
    @Test void testDecoratorChainingBehavior() {
        py.exec("""
            def inc(f):
                def w():
                    return f() + 10
                return w
            
            @inc
            def get():
                return 1
            
            print(get())
            """);
        assertEquals("", err());
        assertEquals("11", out());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Phase 11: loop control, exception clauses, generators, execfile
    // ═══════════════════════════════════════════════════════════════════

    @Test void testBreakAndContinueInWhile() {
        py.exec("""
            i = 0
            while i < 5:
                i = i + 1
                if i == 2:
                    continue
                if i == 4:
                    break
                print(i)
            print("after", i)
            """);
        assertEquals("1\n3\nafter 4", out());
    }

    @Test void testBreakAndContinueInFor() {
        py.exec("""
            for i in [1, 2, 3, 4, 5]:
                if i == 2:
                    continue
                if i == 4:
                    break
                print(i)
            print("done")
            """);
        assertEquals("1\n3\ndone", out());
    }

    @Test void testNestedLoopBreakContinue() {
        py.exec("""
            i = 0
            while i < 3:
                j = 0
                while j < 3:
                    j = j + 1
                    if j == 2:
                        continue
                    if j == 3:
                        break
                    print(i, j)
                i = i + 1
            """);
        assertEquals("0 1\n1 1\n2 1", out());
    }

    @Test void testMultipleExceptClauses() {
        py.exec("""
            try:
                print(1 / 0)
            except TypeError:
                print("type")
            except ZeroDivisionError:
                print("zero")
            """);
        assertEquals("zero", out());
        assertEquals("", err());
    }

    @Test void testExceptElseRunsOnlyOnSuccess() {
        py.exec("""
            try:
                print("ok")
            except Exception:
                print("err")
            else:
                print("else-branch")
            finally:
                print("finally")
            """);
        assertEquals("ok\nelse-branch\nfinally", out());
    }

    @Test void testExceptExceptionIsCatchAll() {
        py.exec("""
            try:
                print(1 / 0)
            except Exception:
                print("caught")
            """);
        assertEquals("caught", out());
    }

    @Test void testUnmatchedExceptionPropagates() {
        assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                print(1 / 0)
            except ValueError:
                print("value")
            """));
    }

    @Test void testTracebackReportsFailingLine() {
        py.exec("""
            x = 1
            y = 0
            print(x / y)
            """);
        assertTrue(err().contains("line 3"), err());
        assertTrue(err().contains("ZeroDivisionError"), err());
    }

    @Test void testTracebackIncludesFunctionFrames() {
        py.exec("""
            def inner():
                return 1 / 0

            def outer():
                return inner()

            outer()
            """);
        assertTrue(err().contains("in inner"), err());
        assertTrue(err().contains("in outer"), err());
        assertTrue(err().contains("line 2"), err());
        assertTrue(err().contains("line 5"), err());
        assertTrue(err().contains("line 7"), err());
    }

    @Test void testTracebackQuotesSourceLine() {
        py.exec("""
            a = 1
            b = a / 0
            """);
        assertTrue(err().contains("b = a / 0"), err());
    }

    @Test void testNextWalksAnIterator() {
        py.exec("""
            it = iter([10, 20])
            print(next(it))
            print(next(it))
            print(next(it, "done"))
            """);
        assertEquals("10\n20\ndone", out());
        assertEquals("", err());
    }

    @Test void testNextOnGenerator() {
        py.exec("""
            def gen():
                yield "a"
                yield "b"

            g = gen()
            print(next(g))
            print(next(g))
            print(next(g, "end"))
            """);
        assertEquals("a\nb\nend", out());
    }

    @Test void testIterOverStringAndForLoop() {
        py.exec("""
            for ch in iter("abc"):
                print(ch)
            """);
        assertEquals("a\nb\nc", out());
    }

    @Test void testTryFinallyWithoutExcept() {
        py.exec("""
            try:
                print("body")
            finally:
                print("finally")
            """);
        assertEquals("body\nfinally", out());
    }

    @Test void testTryFinallyRunsOnException() {
        assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                print(1 / 0)
            finally:
                print("finally")
            """));
        assertEquals("finally", out());
    }

    @Test void testGeneratorYieldsValues() {
        py.exec("""
            def gen():
                yield 1
                yield 2

            for v in gen():
                print(v)
            """);
        assertEquals("1\n2", out());
        assertEquals("", err());
    }

    @Test void testExecfileRunsScriptInCurrentScope() throws IOException {
        Path script = Files.createTempFile("jython3-execfile-", ".py");
        try {
            Files.writeString(script, "print(\"from child\")\nx = 7\n");
            String path = script.toString().replace("\\", "\\\\").replace("'", "\\'");
            py.exec("execfile('" + path + "')\nprint(\"x is\", x)");
            assertEquals("from child\nx is 7", out());
        } finally {
            Files.deleteIfExists(script);
        }
    }

    // ── Sprint 2: jumps must run pending finally bodies ──────────────

    @Test void testBreakRunsFinally() {
        py.exec("""
            i = 0
            while i < 5:
                i = i + 1
                try:
                    if i == 3:
                        break
                finally:
                    print("fin", i)
            print("after", i)
            """);
        assertEquals("fin 1\nfin 2\nfin 3\nafter 3", out());
        assertEquals("", err());
    }

    @Test void testContinueRunsFinally() {
        py.exec("""
            for i in [1, 2, 3]:
                try:
                    if i == 2:
                        continue
                    print("body", i)
                finally:
                    print("fin", i)
            """);
        assertEquals("body 1\nfin 1\nfin 2\nbody 3\nfin 3", out());
    }

    @Test void testReturnRunsFinally() {
        py.exec("""
            def f():
                try:
                    return 7
                finally:
                    print("cleanup")

            print(f())
            """);
        assertEquals("cleanup\n7", out());
    }

    @Test void testNestedFinallyOnBreak() {
        py.exec("""
            while True:
                try:
                    try:
                        break
                    finally:
                        print("inner")
                finally:
                    print("outer")
            print("done")
            """);
        assertEquals("inner\nouter\ndone", out());
    }

    @Test void testBreakInsideTryExceptLeavesNoStaleHandler() {
        py.exec("""
            for i in [1, 2, 3]:
                try:
                    if i == 2:
                        break
                except ValueError:
                    print("never")
            print("ok", i)
            """);
        assertEquals("ok 2", out());
        assertEquals("", err());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  24. Raise, except, and custom exception classes (Commit B)
    // ═══════════════════════════════════════════════════════════════════

    @Test void testBareRaiseUsesTheOutermostActiveHandler() {
        InterpreterException e = assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                raise ValueError("outer")
            except ValueError:
                try:
                    raise TypeError("inner")
                except TypeError:
                    pass
                raise
            """));
        PyException value = assertInstanceOf(PyException.class, e.value());
        assertEquals("ValueError", value.typeName());
        assertEquals("outer", value.message());
    }

    @Test void testBareRaiseInsideNestedHandlerUsesTheInnerValue() {
        py.exec("""
            try:
                try:
                    raise ValueError("outer")
                except ValueError:
                    try:
                        raise TypeError("inner")
                    except TypeError:
                        raise
            except TypeError as caught:
                saved = caught
            """);
        PyException saved = assertInstanceOf(PyException.class, py.getGlobal("saved"));
        assertEquals("TypeError", saved.typeName());
        assertEquals("inner", saved.message());
        assertNotNull(saved.context(), "the inner exception still records the outer as context");
        assertEquals("ValueError", saved.context().typeName());
    }

    @Test void testNewExceptionWhileHandlingRecordsContext() {
        InterpreterException e = assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                raise ValueError("original")
            except ValueError:
                raise TypeError("replacement")
            """));
        PyException value = assertInstanceOf(PyException.class, e.value());
        assertEquals("TypeError", value.typeName());
        assertEquals("replacement", value.message());
        assertNotNull(value.context(), "a replacement exception keeps the handled one as context");
        assertEquals("ValueError", value.context().typeName());
        assertEquals("original", value.context().message());
    }

    @Test void testBareRaiseOutsideHandlerIsAnError() {
        InterpreterException e = assertThrows(InterpreterException.class, () -> py.eval("raise"));
        assertEquals("RuntimeError", e.typeName());
        assertTrue(((PyException) e.value()).message().contains("No active exception"),
                ((PyException) e.value()).message());
    }

    @Test void testBareRaiseDoesNotLeakIntoACalledFunction() {
        py.exec("""
            def probe():
                raise
            try:
                raise ValueError("outer")
            except ValueError:
                try:
                    probe()
                except RuntimeError:
                    result = "isolated"
            """);
        assertEquals("isolated", py.getGlobal("result").toString());
    }

    @Test void testBreakInsideNestedHandlerRestoresOuterException() {
        InterpreterException e = assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                raise ValueError("outer")
            except ValueError:
                while True:
                    try:
                        raise TypeError("inner")
                    except TypeError:
                        break
                raise
            """));
        PyException value = assertInstanceOf(PyException.class, e.value());
        assertEquals("ValueError", value.typeName());
        assertEquals("outer", value.message());
    }

    @Test void testExceptionFromHandlerDoesNotLeaveStaleActiveValue() {
        InterpreterException e = assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                raise ValueError("outer")
            except ValueError:
                try:
                    try:
                        raise TypeError("inner")
                    except TypeError:
                        raise KeyError("third")
                except KeyError:
                    pass
                raise
            """));
        PyException value = assertInstanceOf(PyException.class, e.value());
        assertEquals("ValueError", value.typeName());
        assertEquals("outer", value.message());
    }

    @Test void testContinueInsideNestedHandlerRestoresOuterException() {
        InterpreterException e = assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                raise ValueError("outer")
            except ValueError:
                for i in [1]:
                    try:
                        raise TypeError("inner")
                    except TypeError:
                        continue
                raise
            """));
        PyException value = assertInstanceOf(PyException.class, e.value());
        assertEquals("ValueError", value.typeName());
        assertEquals("outer", value.message());
    }

    @Test void testReturnFromHandlerRunsFinallyCleanly() {
        py.exec("""
            def f():
                try:
                    try:
                        raise ValueError("inner")
                    except ValueError:
                        return "early"
                finally:
                    pass
            result = f()
            """);
        assertEquals("early", py.getGlobal("result").toString());
        assertEquals("", err());
    }

    @Test void testExceptNonExceptionClassIsTypeError() {
        py.exec("""
            class Plain:
                pass
            try:
                try:
                    raise ValueError("x")
                except Plain:
                    pass
            except TypeError:
                result = "rejected"
            """);
        assertEquals("rejected", py.getGlobal("result").toString());
    }

    @Test void testExceptNonClassValueIsTypeError() {
        py.exec("""
            try:
                try:
                    raise ValueError("x")
                except 42:
                    pass
            except TypeError:
                result = "rejected"
            """);
        assertEquals("rejected", py.getGlobal("result").toString());
    }

    @Test void testRaiseNonExceptionValueIsTypeError() {
        py.exec("""
            try:
                raise "boom"
            except TypeError:
                result = "rejected"
            """);
        assertEquals("rejected", py.getGlobal("result").toString());
    }

    @Test void testRaiseOfANonExceptionRecordsContext() {
        py.exec("""
            try:
                raise ValueError("outer")
            except ValueError:
                try:
                    raise "boom"
                except TypeError as bad:
                    saved = bad
            """);
        PyException saved = assertInstanceOf(PyException.class, py.getGlobal("saved"));
        assertEquals("TypeError", saved.typeName());
        assertNotNull(saved.context());
        assertEquals("ValueError", saved.context().typeName());
    }

    @Test void testCustomExceptionClassIsCaughtByItsParent() {
        py.exec("""
            class MyError(ValueError):
                pass
            try:
                raise MyError("custom")
            except ValueError as e:
                saved = e
            """);
        PyException saved = assertInstanceOf(PyException.class, py.getGlobal("saved"));
        assertEquals("MyError", saved.typeName());
        assertEquals("custom", saved.message());
        assertTrue(saved.type().isSubclassOf(py.exceptionTypes().type("ValueError")),
                "the custom class must inherit the real ValueError class");
    }

    @Test void testExceptionClassCallProducesExceptionValue() {
        py.exec("""
            class MyError(Exception):
                pass
            value = MyError("boom")
            """);
        PyException value = assertInstanceOf(PyException.class, py.getGlobal("value"));
        assertEquals("MyError", value.typeName());
        assertEquals("boom", value.message());
    }

    @Test void testRaiseClassWithAndWithoutArguments() {
        py.exec("""
            class E(Exception):
                pass
            try:
                raise E
            except E as first:
                a = first
            try:
                raise E("boom")
            except E as second:
                b = second
            try:
                try:
                    raise E("again")
                except E as original:
                    raise original
            except E as reraised:
                c = reraised
            """);
        assertEquals("", ((PyException) py.getGlobal("a")).message());
        assertEquals("boom", ((PyException) py.getGlobal("b")).message());
        assertEquals("again", ((PyException) py.getGlobal("c")).message());
    }

    @Test void testRaiseValueReraisesTheSameObject() {
        py.exec("""
            try:
                try:
                    raise ValueError("once")
                except ValueError as original:
                    raise original
            except ValueError as again:
                same = original is again
            """);
        assertEquals("True", py.getGlobal("same").toString());
    }

    @Test void testRaiseValueKeepsIdentityWhenGainingContext() {
        py.exec("""
            try:
                try:
                    raise ValueError("outer")
                except ValueError as original:
                    saved_original = original
                    try:
                        raise TypeError("inner")
                    except TypeError:
                        raise original
            except ValueError as caught:
                same = saved_original is caught
                saved_caught = caught
            """);
        assertEquals("True", py.getGlobal("same").toString());
        PyException caught = assertInstanceOf(PyException.class, py.getGlobal("saved_caught"));
        assertNotNull(caught.context(), "the re-raise sets the handled TypeError as context");
        assertEquals("TypeError", caught.context().typeName());
    }

    @Test void testIsInstanceAndTypeSeeTheExceptionClass() {
        py.exec("""
            class MyError(ValueError):
                pass
            try:
                raise MyError("x")
            except ValueError as e:
                by_parent = isinstance(e, ValueError)
                by_other = isinstance(e, KeyError)
                name = type(e)
                saved = e
            """);
        assertEquals("True", py.getGlobal("by_parent").toString());
        assertEquals("False", py.getGlobal("by_other").toString());
        assertEquals("<class 'MyError'>", py.getGlobal("name").toString());
        assertInstanceOf(PyException.class, py.getGlobal("saved"));
    }

    @Test void testExceptTupleCatchesMatchingType() {
        py.exec("""
            events = []
            try:
                raise ValueError("tuple-match")
            except (TypeError, ValueError) as e:
                events.append("caught")
                events.append(isinstance(e, ValueError))
            """);
        assertEquals("", err());
        assertEquals("['caught', True]", py.getGlobal("events").toString());
    }

    @Test void testExceptTupleSkipsNonMatchingType() {
        py.exec("""
            events = []
            try:
                try:
                    raise KeyError("missing")
                except (TypeError, ValueError):
                    events.append("wrong")
            except KeyError:
                events.append("outer-caught")
            """);
        assertEquals("", err());
        assertEquals("['outer-caught']", py.getGlobal("events").toString());
    }

    @Test void testExceptTupleWithNonClassRaisesTypeError() {
        assertThrows(InterpreterException.class, () -> py.eval("""
            try:
                raise RuntimeError("boom")
            except (ValueError, "not a class"):
                pass
            """));
    }

    @Test void testFinallyRunsWhenExceptionRaisedInsideExcept() {
        py.exec("""
            events = []
            try:
                try:
                    raise ValueError("original")
                except ValueError:
                    raise TypeError("replacement")
                finally:
                    events.append("inner_cleanup")
            except TypeError:
                events.append("outer_caught")
            """);
        assertEquals("", err());
        assertEquals("['inner_cleanup', 'outer_caught']", py.getGlobal("events").toString());
    }

    @Test void testNestedFinallyRunsWhenExceptionRaisedInsideExcept() {
        py.exec("""
            events = []
            try:
                try:
                    try:
                        raise ValueError("original")
                    except ValueError:
                        raise TypeError("replacement")
                    finally:
                        events.append("inner_cleanup")
                finally:
                    events.append("outer_cleanup")
            except TypeError:
                events.append("outer_caught")
            """);
        assertEquals("", err());
        assertEquals("['inner_cleanup', 'outer_cleanup', 'outer_caught']", py.getGlobal("events").toString());
    }

    @Test void testAsVariableDeletedAfterExceptBlock() {
        py.exec("""
            deleted = False
            try:
                try:
                    raise ValueError("my-val")
                except ValueError as err_var:
                    inside = err_var is not None
                check = err_var
            except NameError:
                deleted = True
            """);
        assertEquals("", err());
        assertEquals("True", py.getGlobal("inside").toString());
        assertEquals("True", py.getGlobal("deleted").toString());
    }

    /**
     * Locks down the exact traceback layout before the exception model is
     * reworked. If this test changes, the change is intentional.
     */
    @Test void testTracebackFormatGolden() {
        py.exec("""
            def divide(a, b):
                return a / b

            divide(1, 0)
            """);
        String expected = """
            Traceback (most recent call last):
              File "<stdin>", line 4, in <module>
                divide(1, 0)
              File "<stdin>", line 2, in divide
                return a / b
            ZeroDivisionError: division by zero""";
        assertEquals(expected, err());
    }
}
