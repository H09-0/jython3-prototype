/*====================================================================
 * Jython3Interpreter.java
 *
 * Expanded prototype of a Python‑3‑compatible interpreter that runs on the
 * Java Virtual Machine (Java 21+).  This version adds:
 *   • Variable storage/retrieval (`STORE_FAST`, `LOAD_FAST`).
 *   • Basic integer arithmetic (`ADD`, `SUBTRACT`).
 *   • Support for simple assignments (e.g. `x = 10`).
 *   • Support for binary expressions inside `print` (e.g. `print(x + 5)`).
 *====================================================================*/

package org.example.jython3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core interpreter for the prototype.  The implementation is intentionally
 * simple – it uses a hand‑written parser that recognises a tiny subset of
 * Python 3 syntax.  The design keeps the runtime stack‑machine separate from the
 * compiler so that future extensions (byte‑code, more op‑codes, etc.) can be
 * added without breaking the existing flow.
 */
public class Jython3Interpreter {

    /* --------------------------------------------------------------------
     *  Op‑code set – the interpreter operates on a small instruction list.
     * -------------------------------------------------------------------- */
    private enum OpCode {
        LOAD_CONST,   // push the pending constant (int or string) onto the stack
        STORE_FAST,   // pop value and store it under the pending name
        LOAD_FAST,    // push the variable value onto the stack
        ADD,          // binary addition of two integer operands
        SUBTRACT,     // binary subtraction of two integer operands
        PRINT,        // pop value and write it to System.out
        HALT          // end of program
    }

    /* --------------------------------------------------------------------
     *  Pending operand holders – the compile stage records information that
     *  the runtime needs (constant values, variable names).  After an opcode
     *  consumes the pending data the field is cleared so subsequent op‑codes
     *  do not reuse stale values.
     * -------------------------------------------------------------------- */
    private String pendingStringConstant = null;   // for string literals
    private Integer pendingIntConstant = null;    // for integer literals
    private String pendingVarName = null;         // for LOAD_FAST / STORE_FAST

    /**
     * Execute a single line of Python‑3 source code.
     *
     * @param source a statement such as `print('hi')`, `x = 10`, or
     *               `print(x + 5)`.
     * @throws IllegalArgumentException if the source cannot be parsed.
     */
    public void exec(String source) {
        // Reset pending fields before each compilation.
        pendingStringConstant = null;
        pendingIntConstant = null;
        pendingVarName = null;

        OpCode[] program = compile(source);
        run(program);
    }

    /* --------------------------------------------------------------------
     *  Very small hand‑written compiler.
     * -------------------------------------------------------------------- */
    private static final Pattern ASSIGN_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(-?\\d+)$");
    private static final Pattern PRINT_PATTERN =
            Pattern.compile("^print\\((.*)\\)$");
    private static final Pattern BINARY_EXPR_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*([+\\-])\\s*(-?\\d+)$");
    private static final Pattern REVERSED_BINARY_EXPR_PATTERN =
            Pattern.compile("^(-?\\d+)\\s*([+\\-])\\s*([a-zA-Z_][a-zA-Z0-9_]*)$");
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern STRING_LITERAL_PATTERN =
            Pattern.compile("^(['\"]).*\\1$");

    /**
     * Compile a one‑line source string into a tiny instruction array.
     */
    private OpCode[] compile(String src) {
        src = src.strip();

        // ---------------------------------------------------------------
        // 1️⃣  Assignment:  x = 10
        // ---------------------------------------------------------------
        Matcher assign = ASSIGN_PATTERN.matcher(src);
        if (assign.matches()) {
            pendingVarName = assign.group(1);
            pendingIntConstant = Integer.valueOf(assign.group(2));
            return new OpCode[] { OpCode.LOAD_CONST, OpCode.STORE_FAST, OpCode.HALT };
        }

        // ---------------------------------------------------------------
        // 2️⃣  Print statement – analyse the inner expression.
        // ---------------------------------------------------------------
        Matcher print = PRINT_PATTERN.matcher(src);
        if (print.matches()) {
            String expr = print.group(1).strip();

            // ── a) plain string literal
            if (STRING_LITERAL_PATTERN.matcher(expr).matches()) {
                pendingStringConstant = expr.substring(1, expr.length() - 1);
                return new OpCode[] { OpCode.LOAD_CONST, OpCode.PRINT, OpCode.HALT };
            }

            // ── b) plain identifier (e.g. print(x))
            if (IDENTIFIER_PATTERN.matcher(expr).matches()) {
                pendingVarName = expr;
                return new OpCode[] { OpCode.LOAD_FAST, OpCode.PRINT, OpCode.HALT };
            }

            // ── c) binary expression identifier <op> integer   (x + 5 or x - 3)
            Matcher bin = BINARY_EXPR_PATTERN.matcher(expr);
            if (bin.matches()) {
                pendingVarName = bin.group(1);
                String op = bin.group(2);
                pendingIntConstant = Integer.valueOf(bin.group(3));
                if ("+".equals(op)) {
                    return new OpCode[] { OpCode.LOAD_FAST, OpCode.LOAD_CONST, OpCode.ADD, OpCode.PRINT, OpCode.HALT };
                } else {
                    return new OpCode[] { OpCode.LOAD_FAST, OpCode.LOAD_CONST, OpCode.SUBTRACT, OpCode.PRINT, OpCode.HALT };
                }
            }

            // ── d) binary expression integer <op> identifier   (5 + x or 7 - y)
            Matcher revBin = REVERSED_BINARY_EXPR_PATTERN.matcher(expr);
            if (revBin.matches()) {
                pendingIntConstant = Integer.valueOf(revBin.group(1));
                String op = revBin.group(2);
                pendingVarName = revBin.group(3);
                if ("+".equals(op)) {
                    return new OpCode[] { OpCode.LOAD_CONST, OpCode.LOAD_FAST, OpCode.ADD, OpCode.PRINT, OpCode.HALT };
                } else {
                    return new OpCode[] { OpCode.LOAD_CONST, OpCode.LOAD_FAST, OpCode.SUBTRACT, OpCode.PRINT, OpCode.HALT };
                }
            }

            // ── e) integer literal (print(5)) – treat as constant
            if (expr.matches("-?\\d+")) {
                pendingIntConstant = Integer.valueOf(expr);
                return new OpCode[] { OpCode.LOAD_CONST, OpCode.PRINT, OpCode.HALT };
            }
        }

        // ---------------------------------------------------------------
        // If none of the patterns matched, we do not understand the code.
        // ---------------------------------------------------------------
        throw new IllegalArgumentException("Unsupported syntax: " + src);
    }

    /* --------------------------------------------------------------------
     *  Runtime – a tiny stack machine with a single local frame.
     * -------------------------------------------------------------------- */
    private final Map<String, PyObject> locals = new HashMap<>();

    private void run(OpCode[] program) {
        Deque<PyObject> stack = new ArrayDeque<>();
        int pc = 0;
        while (pc < program.length) {
            OpCode op = program[pc];
            switch (op) {
                case LOAD_CONST -> {
                    if (pendingStringConstant != null) {
                        stack.push(new PyString(pendingStringConstant));
                        pendingStringConstant = null;
                    } else if (pendingIntConstant != null) {
                        stack.push(new PyInt(pendingIntConstant));
                        pendingIntConstant = null;
                    } else {
                        // Defensive fallback – should never happen
                        stack.push(PyObject.NULL);
                    }
                }
                case STORE_FAST -> {
                    PyObject value = stack.pop();
                    locals.put(pendingVarName, value);
                    pendingVarName = null;
                }
                case LOAD_FAST -> {
                    PyObject value = locals.get(pendingVarName);
                    if (value == null) {
                        throw new RuntimeException("NameError: name '" + pendingVarName + "' is not defined");
                    }
                    stack.push(value);
                    pendingVarName = null;
                }
                case ADD -> {
                    PyObject right = stack.pop();
                    PyObject left = stack.pop();
                    if (left instanceof PyInt li && right instanceof PyInt ri) {
                        stack.push(new PyInt(li.value + ri.value));
                    } else {
                        throw new RuntimeException("TypeError: unsupported operand type(s) for +");
                    }
                }
                case SUBTRACT -> {
                    PyObject right = stack.pop();
                    PyObject left = stack.pop();
                    if (left instanceof PyInt li && right instanceof PyInt ri) {
                        stack.push(new PyInt(li.value - ri.value));
                    } else {
                        throw new RuntimeException("TypeError: unsupported operand type(s) for -");
                    }
                }
                case PRINT -> {
                    PyObject value = stack.pop();
                    System.out.println(value);
                }
                case HALT -> {
                    return;
                }
                default -> throw new IllegalStateException("Unexpected opcode: " + op);
            }
            pc++;
        }
    }

    /* --------------------------------------------------------------------
     *  Runtime object hierarchy.
     * -------------------------------------------------------------------- */
    public static abstract class PyObject {
        public static final PyObject NULL = new PyNull();
        @Override public String toString() { return "null"; }
    }
    private static final class PyNull extends PyObject {}
    private static final class PyString extends PyObject {
        private final String value;
        PyString(String v) { this.value = v; }
        @Override public String toString() { return value; }
    }
    private static final class PyInt extends PyObject {
        private final int value;
        PyInt(int v) { this.value = v; }
        @Override public String toString() { return Integer.toString(value); }
    }
}
