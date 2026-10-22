/*====================================================================
 * Jython3Interpreter.java – Phase 4 (ANTLR4 parser integration)
 *
 * Architecture:
 *   Source → ANTLR4 Lexer/Parser → ParseTree → CompileVisitor →
 *   OpCode[] + auxQueue → run() stack‑machine → output
 *
 * Supported Python‑3 subset (defined by Jython3.g4):
 *   • Integer and string literals
 *   • Variable assignment  (x = 10)
 *   • Arithmetic with full precedence  (+, -, *, /)
 *   • print() built‑in
 *   • if / else  (single‑statement body)
 *   • while  (single‑statement body)
 *   • import (local modules/ directory)
 *
 * Modern Java 21 features used:
 *   • sealed interface + records for PyObject hierarchy
 *   • Pattern‑matching instanceof & switch
 *   • Enhanced switch expressions
 *   • var (local type inference)
 *====================================================================*/

package org.example.jython3;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * Core interpreter.  Call {@link #exec(String)} to execute a single line
 * of Python‑3 source code.
 */
public class Jython3Interpreter {

    // ─── Op‑code set ──────────────────────────────────────────────────────
    private enum OpCode {
        LOAD_CONST,    // operand: Integer or String  (from instrArgs)
        LOAD_FAST,    // operand: variable name (String)
        STORE_FAST,   // operand: variable name (String)
        ADD, SUBTRACT, MULTIPLY, DIVIDE,
        PRINT,
        JUMP_IF_FALSE, // operand: target pc (Integer)
        JUMP,          // operand: target pc (Integer)
        IMPORT_MODULE, // operand: module name (String)
        HALT
    }

    // ─── Runtime state ────────────────────────────────────────────────────
    /**
     * Per-instruction operand array.  Parallel to the OpCode[]; each slot
     * holds the operand for that instruction (or null for no-operand opcodes).
     * Using a per-instruction array avoids auxPtr desync when jumps skip
     * over instructions whose operands have not been consumed yet.
     */
    private Object[] instrArgs;
    /** Variable storage (single locals frame). */
    private final Map<String, PyObject> locals = new HashMap<>();
    /** Operand stack – kept as a field so helper methods can access it. */
    private Deque<PyObject> stack;

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Execute a single line of Python‑3 source code.
     *
     * @param source e.g. {@code "print(x + 5)"}
     */
    public void exec(String source) {
        instrArgs = null;
        stack = new ArrayDeque<>();
        try {
            run(compile(source.strip()));
        } catch (InterpreterException e) {
            System.err.println("Traceback (most recent call last):");
            System.err.println("  File \"<stdin>\", line 1, in <module>");
            System.err.println("    " + source);
            System.err.println(e.getMessage());
        }
    }

    // ─── ANTLR compilation ────────────────────────────────────────────────

    private OpCode[] compile(String src) throws InterpreterException {
        var lexer = new Jython3Lexer(CharStreams.fromString(src));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        var parser = new Jython3Parser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        var prog   = new ArrayList<OpCode>();
        var args   = new ArrayList<Object>();   // parallel list of operands
        new CompileVisitor(prog, args).visit(parser.stat());
        prog.add(OpCode.HALT);
        args.add(null);
        instrArgs = args.toArray();
        return prog.toArray(OpCode[]::new);
    }

    /**
     * Parse‑tree visitor that emits op‑codes into {@code prog} and fills
     * {@link #auxQueue} with the corresponding operands.
     */
    private class CompileVisitor extends Jython3BaseVisitor<Void> {

        private final List<OpCode>  prog;
        private final List<Object>  args;   // parallel operand list

        CompileVisitor(List<OpCode> prog, List<Object> args) {
            this.prog = prog;
            this.args = args;
        }

        /** Emit an opcode with a given operand (may be null for no-operand opcodes). */
        private void emit(OpCode op, Object arg) { prog.add(op); args.add(arg); }
        private void emit(OpCode op)             { emit(op, null); }

        // ── Dispatch nodes ─────────────────────────────────────────────
        @Override public Void visitStat(Jython3Parser.StatContext ctx)               { return visitChildren(ctx); }
        @Override public Void visitSimple_stmt(Jython3Parser.Simple_stmtContext ctx) { return visitChildren(ctx); }
        @Override public Void visitCompound_stmt(Jython3Parser.Compound_stmtContext ctx) { return visitChildren(ctx); }

        // ── Statements ─────────────────────────────────────────────────

        /** print(expr) → compile expr, emit PRINT */
        @Override
        public Void visitPrint_stmt(Jython3Parser.Print_stmtContext ctx) {
            visit(ctx.expr());
            emit(OpCode.PRINT);
            return null;
        }

        /** id = expr → compile expr, emit STORE_FAST id */
        @Override
        public Void visitAssign_stmt(Jython3Parser.Assign_stmtContext ctx) {
            visit(ctx.expr());
            emit(OpCode.STORE_FAST, ctx.ID().getText());
            return null;
        }

        /** import id → emit IMPORT_MODULE id */
        @Override
        public Void visitImport_stmt(Jython3Parser.Import_stmtContext ctx) {
            emit(OpCode.IMPORT_MODULE, ctx.ID().getText());
            return null;
        }

        /**
         * if expr : simple_stmt (else : simple_stmt)?
         *
         * Layout:
         *   <cond>  JUMP_IF_FALSE→afterThen  <then>  JUMP→afterElse
         * afterThen:
         *   <else>   (optional)
         * afterElse:
         */
        @Override
        public Void visitIf_stmt(Jython3Parser.If_stmtContext ctx) {
            visit(ctx.expr());
            // emit JUMP_IF_FALSE with placeholder – fix target after then-block
            int jfIdx = prog.size();
            emit(OpCode.JUMP_IF_FALSE, -1);

            visit(ctx.simple_stmt(0));              // then‑branch

            // emit JUMP over else-block with placeholder
            int jIdx = prog.size();
            emit(OpCode.JUMP, -1);

            // fill JUMP_IF_FALSE target = first instruction of else (or after JUMP)
            int afterThen = prog.size();
            args.set(jfIdx, afterThen);

            if (ctx.ELSE() != null) {
                visit(ctx.simple_stmt(1));          // else‑branch
                args.set(jIdx, prog.size());        // JUMP lands after else
            } else {
                args.set(jIdx, afterThen);          // no else – JUMP is a no-op
            }
            return null;
        }

        /**
         * while expr : simple_stmt
         *
         * Layout:
         * loopStart:  <cond>  JUMP_IF_FALSE→afterLoop  <body>  JUMP→loopStart
         * afterLoop:
         */
        @Override
        public Void visitWhile_stmt(Jython3Parser.While_stmtContext ctx) {
            int loopStart = prog.size();
            visit(ctx.expr());
            int jfIdx = prog.size();
            emit(OpCode.JUMP_IF_FALSE, -1);         // placeholder
            visit(ctx.simple_stmt());
            emit(OpCode.JUMP, loopStart);            // jump back to condition
            args.set(jfIdx, prog.size());            // exit target
            return null;
        }

        // ── Expressions ────────────────────────────────────────────────

        @Override
        public Void visitMulDiv(Jython3Parser.MulDivContext ctx) {
            visit(ctx.expr(0)); visit(ctx.expr(1));
            emit(ctx.op.getText().equals("*") ? OpCode.MULTIPLY : OpCode.DIVIDE);
            return null;
        }

        @Override
        public Void visitAddSub(Jython3Parser.AddSubContext ctx) {
            visit(ctx.expr(0)); visit(ctx.expr(1));
            emit(ctx.op.getText().equals("+") ? OpCode.ADD : OpCode.SUBTRACT);
            return null;
        }

        @Override public Void visitParen(Jython3Parser.ParenContext ctx) { visit(ctx.expr()); return null; }

        @Override
        public Void visitInt(Jython3Parser.IntContext ctx) {
            emit(OpCode.LOAD_CONST, Integer.valueOf(ctx.INT().getText()));
            return null;
        }

        @Override
        public Void visitStr(Jython3Parser.StrContext ctx) {
            String raw = ctx.STRING().getText();
            emit(OpCode.LOAD_CONST, raw.substring(1, raw.length() - 1));
            return null;
        }

        @Override
        public Void visitId(Jython3Parser.IdContext ctx) {
            emit(OpCode.LOAD_FAST, ctx.ID().getText());
            return null;
        }
    }

    // ─── Stack‑machine execution ──────────────────────────────────────────

    private void run(OpCode[] program) throws InterpreterException {
        int pc = 0;
        while (pc < program.length) {
            // Fetch operand for this instruction from the parallel instrArgs array.
            // No auxPtr desync is possible because each slot maps 1-to-1 with its opcode.
            Object arg = instrArgs[pc];
            switch (program[pc]) {
                case LOAD_CONST -> {
                    if (arg instanceof String s)   stack.push(new PyStr(s));
                    else if (arg instanceof Integer i) stack.push(new PyInt(i));
                    else throw new InterpreterException("Unknown constant type: " + arg);
                }
                case LOAD_FAST -> {
                    String name = (String) arg;
                    PyObject v = locals.get(name);
                    if (v == null)
                        throw new InterpreterException("NameError: name '" + name + "' is not defined");
                    stack.push(v);
                }
                case STORE_FAST -> locals.put((String) arg, stack.pop());
                case ADD      -> binaryInt(Integer::sum);
                case SUBTRACT -> binaryInt((a, b) -> a - b);
                case MULTIPLY -> binaryInt((a, b) -> a * b);
                case DIVIDE   -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0)
                            throw new InterpreterException("ZeroDivisionError: division by zero");
                        stack.push(new PyInt(li.value() / ri.value()));
                    } else throw new InterpreterException("TypeError: unsupported operand types for /");
                }
                case PRINT -> System.out.println(stack.pop());
                case JUMP_IF_FALSE -> {
                    var cond = stack.pop();
                    int target = (Integer) arg;
                    if (!isTruthy(cond)) { pc = target; continue; }  // jump directly – no pc++
                }
                case JUMP -> { pc = (Integer) arg; continue; }        // jump directly – no pc++
                case IMPORT_MODULE -> importModule((String) arg);
                case HALT -> { return; }
            }
            pc++;
        }
    }

    /** Functional interface for int binary operations. */
    @FunctionalInterface
    private interface IntBinOp { int apply(int a, int b); }

    private void binaryInt(IntBinOp op) throws InterpreterException {
        var rhs = stack.pop(); var lhs = stack.pop();
        if (lhs instanceof PyInt l && rhs instanceof PyInt r)
            stack.push(new PyInt(op.apply(l.value(), r.value())));
        else
            throw new InterpreterException("TypeError: unsupported operand types for arithmetic");
    }

    private static boolean isTruthy(PyObject v) {
        return switch (v) {
            case PyInt i -> i.value() != 0;
            case PyStr s -> !s.value().isEmpty();
            case PyNull n -> false;
        };
    }

    /** Execute every non‑blank line in {@code modules/<name>.py}. */
    private void importModule(String name) throws InterpreterException {
        String path = "modules/" + name + ".py";
        try (var br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null)
                if (!line.isBlank()) exec(line);
        } catch (IOException e) {
            throw new InterpreterException("ImportError: no module named '" + name + "'");
        }
    }

    // ─── Bytecode generation ──────────────────────────────────────────────

    /**
     * Compile source to a compact integer bytecode array.
     * Encoding: {@code opcode.ordinal() << 16}.
     */
    public int[] compileToBytecode(String src) throws InterpreterException {
        OpCode[] prog = compile(src.strip());
        int[] bc = new int[prog.length];
        for (int i = 0; i < prog.length; i++) {
            int operand = (instrArgs[i] instanceof Integer iv) ? iv : 0;
            bc[i] = (prog[i].ordinal() << 16) | (operand & 0xFFFF);
        }
        return bc;
    }

    // ─── Runtime object hierarchy (Java 21: sealed + records) ────────────

    /** Root of the runtime type hierarchy. */
    public sealed interface PyObject permits PyInt, PyStr, PyNull {}

    /** Python integer. */
    public record PyInt(int value) implements PyObject {
        @Override public String toString() { return Integer.toString(value); }
    }

    /** Python string. */
    public record PyStr(String value) implements PyObject {
        @Override public String toString() { return value; }
    }

    /** Python None. */
    public record PyNull() implements PyObject {
        @Override public String toString() { return "None"; }
    }

    // ─── Error handling ───────────────────────────────────────────────────

    /** All interpreter errors are wrapped in this exception. */
    public static class InterpreterException extends RuntimeException {
        public InterpreterException(String msg) { super(msg); }
    }

    /** ANTLR error listener – converts syntax errors into InterpreterException. */
    private static final class ThrowingErrorListener extends BaseErrorListener {
        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> rec, Object sym,
                                int line, int col, String msg, RecognitionException e) {
            throw new InterpreterException("SyntaxError at " + line + ":" + col + " – " + msg);
        }
    }
}
