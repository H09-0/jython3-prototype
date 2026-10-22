/*====================================================================
 * Jython3Interpreter.java – Phase 5 (Functions & Call Stack)
 *
 * Architecture:
 *   Source → ANTLR4 Lexer/Parser → ParseTree → CompileVisitor →
 *   OpCode[] + instrArgs[] → run() stack‑machine → output
 *
 * Supported Python‑3 subset (defined by Jython3.g4):
 *   • Integer and string literals
 *   • Variable assignment  (x = 10)
 *   • Arithmetic with full precedence  (+, -, *, /)
 *   • print() built‑in
 *   • if / else  (single‑statement body)
 *   • while  (single‑statement body)
 *   • import (local modules/ directory)
 *   • Function definitions:  def name(a, b): body
 *   • Function calls as expressions:  name(1, 2)
 *   • Standalone function calls:  name(1, 2)
 *   • Return statements:  return expr
 *
 * Modern Java 21 features:
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
        LOAD_CONST,       // operand: Integer or String
        LOAD_FAST,       // operand: variable name (String)
        LOAD_GLOBAL,     // operand: variable name from globals (String)
        STORE_FAST,      // operand: variable name (String)
        ADD, SUBTRACT, MULTIPLY, DIVIDE,
        PRINT,
        JUMP_IF_FALSE,   // operand: target pc (Integer)
        JUMP,            // operand: target pc (Integer)
        IMPORT_MODULE,   // operand: module name (String)
        MAKE_FUNCTION,   // operand: function name (String) — pops PyFunction body from stack
        CALL_FUNCTION,   // operand: number of args (Integer)
        RETURN_VALUE,    // no operand — pops top of stack as return value
        POP_TOP,         // discard top of stack (for standalone call_stmt)
        HALT
    }

    // ─── Runtime state ────────────────────────────────────────────────────

    /** Per-instruction operand array, parallel to OpCode[]. */
    private Object[] instrArgs;

    /**
     * Global scope — holds top-level variables AND function objects.
     * Function bodies have their own local scope (Frame.locals).
     */
    private final Map<String, PyObject> globals = new HashMap<>();

    /** Operand stack — shared across the current frame's execution. */
    private Deque<PyObject> stack;

    /**
     * Call stack — each entry is a saved Frame (return address + local scope).
     * When a function is called we push a new Frame; when RETURN_VALUE fires
     * we pop the Frame and restore the caller's state.
     */
    private final Deque<Frame> callStack = new ArrayDeque<>();

    /** Current local scope (starts as the same reference as globals). */
    private Map<String, PyObject> currentLocals;

    /** A saved execution frame for the call stack. */
    private record Frame(OpCode[] program, Object[] args, int returnPc,
                         Map<String, PyObject> locals, Deque<PyObject> stack) {}

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Execute a single line of Python‑3 source code.
     *
     * @param source e.g. {@code "print(x + 5)"}
     */
    public void exec(String source) {
        instrArgs = null;
        stack = new ArrayDeque<>();
        currentLocals = globals;         // top-level code uses globals as locals
        try {
            run(compile(source.strip()));
        } catch (ReturnSignal rs) {
            // return outside a function — silently ignore at top level
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

        var prog = new ArrayList<OpCode>();
        var args = new ArrayList<Object>();
        new CompileVisitor(prog, args).visit(parser.stat());
        prog.add(OpCode.HALT);
        args.add(null);
        instrArgs = args.toArray();
        return prog.toArray(OpCode[]::new);
    }

    /**
     * Compile a function body (a list of simple_stmts separated by ';') into
     * its own OpCode[] + Object[] pair, packaged as a PyFunction.
     */
    private PyFunction compileFunction(String name, List<String> params,
                                        Jython3Parser.Func_bodyContext bodyCtx) {
        var prog = new ArrayList<OpCode>();
        var args = new ArrayList<Object>();
        var visitor = new CompileVisitor(prog, args);
        // visit each simple_stmt inside the body
        for (var stmtCtx : bodyCtx.simple_stmt()) {
            visitor.visit(stmtCtx);
        }
        // implicit return None if no explicit return
        prog.add(OpCode.LOAD_CONST);
        args.add(null);  // will push PyNull
        prog.add(OpCode.RETURN_VALUE);
        args.add(null);
        prog.add(OpCode.HALT);
        args.add(null);
        return new PyFunction(name, params, prog.toArray(OpCode[]::new), args.toArray());
    }

    /**
     * AST visitor: translates parse‑tree nodes into op‑codes.
     */
    private class CompileVisitor extends Jython3BaseVisitor<Void> {

        private final List<OpCode> prog;
        private final List<Object> args;

        CompileVisitor(List<OpCode> prog, List<Object> args) {
            this.prog = prog;
            this.args = args;
        }

        private void emit(OpCode op, Object arg) { prog.add(op); args.add(arg); }
        private void emit(OpCode op)              { emit(op, null); }

        // ── Dispatch ───────────────────────────────────────────────────
        @Override public Void visitStat(Jython3Parser.StatContext ctx)                   { return visitChildren(ctx); }
        @Override public Void visitSimple_stmt(Jython3Parser.Simple_stmtContext ctx)     { return visitChildren(ctx); }
        @Override public Void visitCompound_stmt(Jython3Parser.Compound_stmtContext ctx) { return visitChildren(ctx); }
        @Override public Void visitFunc_body(Jython3Parser.Func_bodyContext ctx)         { return visitChildren(ctx); }

        // ── Statements ─────────────────────────────────────────────────

        @Override
        public Void visitPrint_stmt(Jython3Parser.Print_stmtContext ctx) {
            visit(ctx.expr());
            emit(OpCode.PRINT);
            return null;
        }

        @Override
        public Void visitAssign_stmt(Jython3Parser.Assign_stmtContext ctx) {
            visit(ctx.expr());
            emit(OpCode.STORE_FAST, ctx.ID().getText());
            return null;
        }

        @Override
        public Void visitImport_stmt(Jython3Parser.Import_stmtContext ctx) {
            emit(OpCode.IMPORT_MODULE, ctx.ID().getText());
            return null;
        }

        @Override
        public Void visitReturn_stmt(Jython3Parser.Return_stmtContext ctx) {
            visit(ctx.expr());
            emit(OpCode.RETURN_VALUE);
            return null;
        }

        /** Standalone function call statement — call and discard result */
        @Override
        public Void visitCall_stmt(Jython3Parser.Call_stmtContext ctx) {
            // push function object
            emit(OpCode.LOAD_FAST, ctx.ID().getText());
            // push each argument
            int argCount = 0;
            if (ctx.arg_list() != null) {
                for (var exprCtx : ctx.arg_list().expr()) {
                    visit(exprCtx);
                    argCount++;
                }
            }
            emit(OpCode.CALL_FUNCTION, argCount);
            emit(OpCode.POP_TOP);        // discard return value
            return null;
        }

        /** def name(params): body → compile body separately, emit MAKE_FUNCTION */
        @Override
        public Void visitFunc_def(Jython3Parser.Func_defContext ctx) {
            String funcName = ctx.ID().getText();
            List<String> params = new ArrayList<>();
            if (ctx.param_list() != null) {
                for (var id : ctx.param_list().ID()) {
                    params.add(id.getText());
                }
            }
            // compile the body into a separate PyFunction object
            PyFunction fn = compileFunction(funcName, params, ctx.func_body());
            // at runtime: push the PyFunction, then MAKE_FUNCTION stores it in scope
            emit(OpCode.LOAD_CONST, fn);
            emit(OpCode.MAKE_FUNCTION, funcName);
            return null;
        }

        // ── Control flow ───────────────────────────────────────────────

        @Override
        public Void visitIf_stmt(Jython3Parser.If_stmtContext ctx) {
            visit(ctx.expr());
            int jfIdx = prog.size();
            emit(OpCode.JUMP_IF_FALSE, -1);
            visit(ctx.simple_stmt(0));
            int jIdx = prog.size();
            emit(OpCode.JUMP, -1);
            int afterThen = prog.size();
            args.set(jfIdx, afterThen);
            if (ctx.ELSE() != null) {
                visit(ctx.simple_stmt(1));
                args.set(jIdx, prog.size());
            } else {
                args.set(jIdx, afterThen);
            }
            return null;
        }

        @Override
        public Void visitWhile_stmt(Jython3Parser.While_stmtContext ctx) {
            int loopStart = prog.size();
            visit(ctx.expr());
            int jfIdx = prog.size();
            emit(OpCode.JUMP_IF_FALSE, -1);
            visit(ctx.simple_stmt());
            emit(OpCode.JUMP, loopStart);
            args.set(jfIdx, prog.size());
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

        /** Function call as an expression (keeps return value on stack). */
        @Override
        public Void visitFuncCall(Jython3Parser.FuncCallContext ctx) {
            // push the callable
            emit(OpCode.LOAD_FAST, ctx.ID().getText());
            int argCount = 0;
            if (ctx.arg_list() != null) {
                for (var exprCtx : ctx.arg_list().expr()) {
                    visit(exprCtx);
                    argCount++;
                }
            }
            emit(OpCode.CALL_FUNCTION, argCount);
            return null;
        }
    }

    // ─── Stack‑machine execution ──────────────────────────────────────────

    private void run(OpCode[] program) throws InterpreterException {
        int pc = 0;
        while (pc < program.length) {
            Object arg = instrArgs[pc];
            switch (program[pc]) {
                case LOAD_CONST -> {
                    if (arg == null)               stack.push(new PyNull());
                    else if (arg instanceof String s)   stack.push(new PyStr(s));
                    else if (arg instanceof Integer i)  stack.push(new PyInt(i));
                    else if (arg instanceof PyFunction fn) stack.push(fn);
                    else throw new InterpreterException("Unknown constant type: " + arg);
                }
                case LOAD_FAST -> {
                    String name = (String) arg;
                    // check local scope first, then global
                    PyObject v = currentLocals.get(name);
                    if (v == null) v = globals.get(name);
                    if (v == null)
                        throw new InterpreterException("NameError: name '" + name + "' is not defined");
                    stack.push(v);
                }
                case LOAD_GLOBAL -> {
                    String name = (String) arg;
                    PyObject v = globals.get(name);
                    if (v == null)
                        throw new InterpreterException("NameError: name '" + name + "' is not defined");
                    stack.push(v);
                }
                case STORE_FAST -> currentLocals.put((String) arg, stack.pop());
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
                    if (!isTruthy(cond)) { pc = target; continue; }
                }
                case JUMP -> { pc = (Integer) arg; continue; }
                case IMPORT_MODULE -> importModule((String) arg);
                case MAKE_FUNCTION -> {
                    String funcName = (String) arg;
                    PyObject fn = stack.pop();   // the PyFunction object
                    currentLocals.put(funcName, fn);
                }
                case CALL_FUNCTION -> {
                    int argCount = (Integer) arg;
                    // collect arguments from stack (right-to-left)
                    PyObject[] callArgs = new PyObject[argCount];
                    for (int i = argCount - 1; i >= 0; i--) {
                        callArgs[i] = stack.pop();
                    }
                    // pop the callable
                    PyObject callable = stack.pop();
                    if (callable instanceof PyFunction fn) {
                        // save current state
                        callStack.push(new Frame(program, instrArgs, pc + 1,
                                                  currentLocals, stack));
                        // set up new frame
                        var fnLocals = new HashMap<String, PyObject>();
                        for (int i = 0; i < fn.params().size() && i < argCount; i++) {
                            fnLocals.put(fn.params().get(i), callArgs[i]);
                        }
                        currentLocals = fnLocals;
                        instrArgs = fn.instrArgs();
                        stack = new ArrayDeque<>();
                        program = fn.body();
                        pc = 0;
                        continue;
                    } else {
                        throw new InterpreterException("TypeError: '" + callable + "' is not callable");
                    }
                }
                case RETURN_VALUE -> {
                    PyObject retVal = stack.pop();
                    if (callStack.isEmpty()) {
                        // top-level return — push value and halt
                        stack.push(retVal);
                        return;
                    }
                    // restore caller's frame
                    Frame caller = callStack.pop();
                    program = caller.program();
                    instrArgs = caller.args();
                    pc = caller.returnPc();
                    currentLocals = caller.locals();
                    stack = caller.stack();
                    stack.push(retVal);    // push return value onto caller's stack
                    continue;
                }
                case POP_TOP -> stack.pop();
                case HALT -> { return; }
            }
            pc++;
        }
    }

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
            case PyFunction f -> true;
        };
    }

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

    public sealed interface PyObject permits PyInt, PyStr, PyNull, PyFunction {}

    public record PyInt(int value) implements PyObject {
        @Override public String toString() { return Integer.toString(value); }
    }
    public record PyStr(String value) implements PyObject {
        @Override public String toString() { return value; }
    }
    public record PyNull() implements PyObject {
        @Override public String toString() { return "None"; }
    }

    /** A callable function object holding compiled body + parameter names. */
    public record PyFunction(String name, List<String> params,
                              OpCode[] body, Object[] instrArgs) implements PyObject {
        @Override public String toString() { return "<function " + name + ">"; }
    }

    // ─── Error handling ───────────────────────────────────────────────────

    public static class InterpreterException extends RuntimeException {
        public InterpreterException(String msg) { super(msg); }
    }

    /** Used internally to unwind through RETURN_VALUE at top level. */
    private static class ReturnSignal extends RuntimeException {
        final PyObject value;
        ReturnSignal(PyObject v) { this.value = v; }
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> rec, Object sym,
                                int line, int col, String msg, RecognitionException e) {
            throw new InterpreterException("SyntaxError at " + line + ":" + col + " – " + msg);
        }
    }
}
