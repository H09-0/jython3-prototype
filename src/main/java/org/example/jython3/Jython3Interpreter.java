/*====================================================================
 * Jython3Interpreter.java – Phase 7 (Partial Standard Library)
 *
 * Architecture:
 *   Source → ANTLR4 → ParseTree → CompileVisitor → CompiledCode
 *   CompiledCode = int[] bytecode + Object[] constPool
 *   run() decodes bytecode and executes via stack machine
 *
 * Bytecode encoding: each instruction is a 32-bit int
 *   high 16 bits = OpCode ordinal
 *   low 16 bits  = index into constPool (0xFFFF = no operand)
 *
 * Phase 7 additions:
 *   • PyFloat, PyModule, PyBuiltinFunction runtime types
 *   • LOAD_ATTR opcode  – attribute access (module.attr)
 *   • import math / sys / os  →  built‑in modules via BuiltinModules
 *   • math.sqrt, math.sin, math.cos, math.floor, math.ceil, math.pi, math.e
 *   • sys.argv, sys.version, sys.platform
 *   • os.getcwd(), os.sep, os.name
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

public class Jython3Interpreter {

    // ─── Op‑code set ──────────────────────────────────────────────────────
    enum OpCode {
        LOAD_CONST, LOAD_FAST, LOAD_GLOBAL, STORE_FAST,
        ADD, SUBTRACT, MULTIPLY, DIVIDE,
        PRINT,
        JUMP_IF_FALSE, JUMP,
        IMPORT_MODULE, MAKE_FUNCTION, CALL_FUNCTION, RETURN_VALUE, POP_TOP,
        BUILD_LIST, BINARY_SUBSCR, GET_ITER, FOR_ITER,
        LOAD_ATTR,  // pop object, push object.attr (attr name from constPool)
        HALT
    }

    private static final int NO_ARG = 0xFFFF;

    // ─── CompiledCode ─────────────────────────────────────────────────────
    public record CompiledCode(int[] bytecode, Object[] constPool) {}

    // ─── Runtime state ────────────────────────────────────────────────────
    private final Map<String, PyObject> globals = new HashMap<>();
    private Deque<PyObject> stack;
    private Map<String, PyObject> currentLocals;
    private final Deque<Frame> callStack = new ArrayDeque<>();
    private record Frame(CompiledCode code, int returnPc,
                         Map<String, PyObject> locals, Deque<PyObject> stack) {}

    // ─── Public API ───────────────────────────────────────────────────────

    public void exec(String source) {
        stack = new ArrayDeque<>();
        currentLocals = globals;
        try {
            run(compile(source.strip()));
        } catch (InterpreterException e) {
            System.err.println("Traceback (most recent call last):");
            System.err.println("  File \"<stdin>\", line 1, in <module>");
            System.err.println("    " + source);
            System.err.println(e.getMessage());
        }
    }

    // ─── ANTLR compilation → CompiledCode ─────────────────────────────────

    private CompiledCode compile(String src) throws InterpreterException {
        var lexer = new Jython3Lexer(CharStreams.fromString(src));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);
        var parser = new Jython3Parser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);
        var b = new CodeBuilder();
        new CompileVisitor(b).visit(parser.stat());
        b.emit(OpCode.HALT);
        return b.build();
    }

    // ─── CodeBuilder ──────────────────────────────────────────────────────

    private static class CodeBuilder {
        private final List<Integer> bytecode = new ArrayList<>();
        private final List<Object> constPool = new ArrayList<>();
        private final Map<Object, Integer> constMap = new HashMap<>();
        int addConst(Object val) {
            if (val == null) { int i = constPool.size(); constPool.add(null); return i; }
            return constMap.computeIfAbsent(val, k -> { int i = constPool.size(); constPool.add(k); return i; });
        }
        void emit(OpCode op, Object cv) { bytecode.add((op.ordinal() << 16) | (addConst(cv) & 0xFFFF)); }
        void emitRaw(OpCode op, int raw) { bytecode.add((op.ordinal() << 16) | (raw & 0xFFFF)); }
        void emit(OpCode op)             { bytecode.add((op.ordinal() << 16) | NO_ARG); }
        int size()                       { return bytecode.size(); }
        void patch(int idx, int arg)     { int old = bytecode.get(idx); bytecode.set(idx, (old & 0xFFFF0000) | (arg & 0xFFFF)); }
        CompiledCode build()             { return new CompiledCode(bytecode.stream().mapToInt(Integer::intValue).toArray(), constPool.toArray()); }
    }

    // ─── CompileVisitor ───────────────────────────────────────────────────

    private class CompileVisitor extends Jython3BaseVisitor<Void> {
        private final CodeBuilder b;
        CompileVisitor(CodeBuilder b) { this.b = b; }

        @Override public Void visitStat(Jython3Parser.StatContext c)                   { return visitChildren(c); }
        @Override public Void visitSimple_stmt(Jython3Parser.Simple_stmtContext c)     { return visitChildren(c); }
        @Override public Void visitCompound_stmt(Jython3Parser.Compound_stmtContext c) { return visitChildren(c); }
        @Override public Void visitFunc_body(Jython3Parser.Func_bodyContext c)         { return visitChildren(c); }

        @Override public Void visitPrint_stmt(Jython3Parser.Print_stmtContext c) { visit(c.expr()); b.emit(OpCode.PRINT); return null; }
        @Override public Void visitAssign_stmt(Jython3Parser.Assign_stmtContext c) { visit(c.expr()); b.emit(OpCode.STORE_FAST, c.ID().getText()); return null; }
        @Override public Void visitImport_stmt(Jython3Parser.Import_stmtContext c) { b.emit(OpCode.IMPORT_MODULE, c.ID().getText()); return null; }
        @Override public Void visitReturn_stmt(Jython3Parser.Return_stmtContext c) { visit(c.expr()); b.emit(OpCode.RETURN_VALUE); return null; }

        @Override public Void visitCall_stmt(Jython3Parser.Call_stmtContext c) {
            b.emit(OpCode.LOAD_FAST, c.ID().getText());
            int n = 0; if (c.arg_list() != null) for (var e : c.arg_list().expr()) { visit(e); n++; }
            b.emitRaw(OpCode.CALL_FUNCTION, n); b.emit(OpCode.POP_TOP); return null;
        }
        @Override public Void visitFunc_def(Jython3Parser.Func_defContext c) {
            String name = c.ID().getText();
            var params = new ArrayList<String>();
            if (c.param_list() != null) for (var id : c.param_list().ID()) params.add(id.getText());
            var fb = new CodeBuilder(); var fv = new CompileVisitor(fb);
            for (var s : c.func_body().simple_stmt()) fv.visit(s);
            fb.emit(OpCode.LOAD_CONST, null); fb.emit(OpCode.RETURN_VALUE); fb.emit(OpCode.HALT);
            b.emit(OpCode.LOAD_CONST, new PyFunction(name, params, fb.build()));
            b.emit(OpCode.MAKE_FUNCTION, name); return null;
        }

        @Override public Void visitIf_stmt(Jython3Parser.If_stmtContext c) {
            visit(c.expr()); int jf = b.size(); b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            visit(c.simple_stmt(0)); int j = b.size(); b.emitRaw(OpCode.JUMP, 0);
            b.patch(jf, b.size());
            if (c.ELSE() != null) { visit(c.simple_stmt(1)); b.patch(j, b.size()); }
            else b.patch(j, b.size());
            return null;
        }
        @Override public Void visitWhile_stmt(Jython3Parser.While_stmtContext c) {
            int ls = b.size(); visit(c.expr()); int jf = b.size(); b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            visit(c.simple_stmt()); b.emitRaw(OpCode.JUMP, ls); b.patch(jf, b.size()); return null;
        }
        @Override public Void visitFor_stmt(Jython3Parser.For_stmtContext c) {
            visit(c.expr()); b.emit(OpCode.GET_ITER);
            int lt = b.size(); int fi = b.size(); b.emitRaw(OpCode.FOR_ITER, 0);
            b.emit(OpCode.STORE_FAST, c.ID().getText()); visit(c.simple_stmt());
            b.emitRaw(OpCode.JUMP, lt); b.patch(fi, b.size()); return null;
        }

        @Override public Void visitMulDiv(Jython3Parser.MulDivContext c) { visit(c.expr(0)); visit(c.expr(1)); b.emit(c.op.getText().equals("*") ? OpCode.MULTIPLY : OpCode.DIVIDE); return null; }
        @Override public Void visitAddSub(Jython3Parser.AddSubContext c) { visit(c.expr(0)); visit(c.expr(1)); b.emit(c.op.getText().equals("+") ? OpCode.ADD : OpCode.SUBTRACT); return null; }
        @Override public Void visitParen(Jython3Parser.ParenContext c) { visit(c.expr()); return null; }
        @Override public Void visitInt(Jython3Parser.IntContext c) { b.emit(OpCode.LOAD_CONST, Integer.valueOf(c.INT().getText())); return null; }
        @Override public Void visitStr(Jython3Parser.StrContext c) { String r = c.STRING().getText(); b.emit(OpCode.LOAD_CONST, r.substring(1, r.length()-1)); return null; }
        @Override public Void visitId(Jython3Parser.IdContext c) { b.emit(OpCode.LOAD_FAST, c.ID().getText()); return null; }
        @Override public Void visitFuncCall(Jython3Parser.FuncCallContext c) {
            b.emit(OpCode.LOAD_FAST, c.ID().getText());
            int n = 0; if (c.arg_list() != null) for (var e : c.arg_list().expr()) { visit(e); n++; }
            b.emitRaw(OpCode.CALL_FUNCTION, n); return null;
        }
        @Override public Void visitListLiteral(Jython3Parser.ListLiteralContext c) {
            int n = 0; if (c.expr_list() != null) for (var e : c.expr_list().expr()) { visit(e); n++; }
            b.emitRaw(OpCode.BUILD_LIST, n); return null;
        }
        @Override public Void visitSubscript(Jython3Parser.SubscriptContext c) { visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.BINARY_SUBSCR); return null; }
        /** module.attr  → LOAD_ATTR */
        @Override public Void visitAttrAccess(Jython3Parser.AttrAccessContext c) {
            visit(c.expr()); b.emit(OpCode.LOAD_ATTR, c.ID().getText()); return null;
        }
        /** module.func(args)  → LOAD_ATTR + CALL_FUNCTION */
        @Override public Void visitAttrCall(Jython3Parser.AttrCallContext c) {
            visit(c.expr()); b.emit(OpCode.LOAD_ATTR, c.ID().getText());
            int n = 0; if (c.arg_list() != null) for (var e : c.arg_list().expr()) { visit(e); n++; }
            b.emitRaw(OpCode.CALL_FUNCTION, n); return null;
        }
    }

    // ─── Stack‑machine execution ──────────────────────────────────────────

    private void run(CompiledCode code) throws InterpreterException {
        int[] bc = code.bytecode(); Object[] pool = code.constPool(); int pc = 0;
        while (pc < bc.length) {
            int instr = bc[pc]; OpCode op = OpCode.values()[instr >>> 16]; int ai = instr & 0xFFFF;
            switch (op) {
                case LOAD_CONST -> {
                    if (ai == NO_ARG) { stack.push(new PyNull()); break; }
                    Object v = pool[ai];
                    if (v == null) stack.push(new PyNull());
                    else if (v instanceof String s) stack.push(new PyStr(s));
                    else if (v instanceof Integer i) stack.push(new PyInt(i));
                    else if (v instanceof PyFunction f) stack.push(f);
                    else throw new InterpreterException("Unknown const: " + v);
                }
                case LOAD_FAST -> { String n = (String) pool[ai]; PyObject v = currentLocals.get(n); if (v == null) v = globals.get(n); if (v == null) throw new InterpreterException("NameError: name '" + n + "' is not defined"); stack.push(v); }
                case LOAD_GLOBAL -> { String n = (String) pool[ai]; PyObject v = globals.get(n); if (v == null) throw new InterpreterException("NameError: name '" + n + "' is not defined"); stack.push(v); }
                case STORE_FAST -> currentLocals.put((String) pool[ai], stack.pop());
                case ADD      -> binaryInt(Integer::sum);
                case SUBTRACT -> binaryInt((a, b2) -> a - b2);
                case MULTIPLY -> binaryInt((a, b2) -> a * b2);
                case DIVIDE   -> { var r = stack.pop(); var l = stack.pop(); if (l instanceof PyInt li && r instanceof PyInt ri) { if (ri.value()==0) throw new InterpreterException("ZeroDivisionError: division by zero"); stack.push(new PyInt(li.value()/ri.value())); } else throw new InterpreterException("TypeError: unsupported operand types for /"); }
                case PRINT -> System.out.println(stack.pop());
                case JUMP_IF_FALSE -> { if (!isTruthy(stack.pop())) { pc = ai; continue; } }
                case JUMP -> { pc = ai; continue; }
                case IMPORT_MODULE -> importModule((String) pool[ai]);
                case MAKE_FUNCTION -> currentLocals.put((String) pool[ai], stack.pop());
                case CALL_FUNCTION -> {
                    int argc = ai; var args = new PyObject[argc];
                    for (int i = argc-1; i >= 0; i--) args[i] = stack.pop();
                    PyObject callable = stack.pop();
                    if (callable instanceof PyFunction fn) {
                        callStack.push(new Frame(code, pc+1, currentLocals, stack));
                        var fl = new HashMap<String, PyObject>();
                        for (int i = 0; i < fn.params().size() && i < argc; i++) fl.put(fn.params().get(i), args[i]);
                        currentLocals = fl; stack = new ArrayDeque<>();
                        code = fn.code(); bc = code.bytecode(); pool = code.constPool(); pc = 0; continue;
                    } else if (callable instanceof PyBuiltinFunction bf) {
                        stack.push(bf.fn().apply(args));
                    } else throw new InterpreterException("TypeError: '" + callable + "' is not callable");
                }
                case RETURN_VALUE -> {
                    PyObject rv = stack.pop();
                    if (callStack.isEmpty()) { stack.push(rv); return; }
                    Frame cf = callStack.pop(); code = cf.code(); bc = code.bytecode(); pool = code.constPool();
                    pc = cf.returnPc(); currentLocals = cf.locals(); stack = cf.stack(); stack.push(rv); continue;
                }
                case POP_TOP -> stack.pop();
                case BUILD_LIST -> { int n = ai; var items = new ArrayList<PyObject>(n); for (int i = 0; i < n; i++) items.addFirst(stack.pop()); stack.push(new PyList(items)); }
                case BINARY_SUBSCR -> {
                    PyObject idx = stack.pop(); PyObject ctr = stack.pop();
                    if (ctr instanceof PyList list && idx instanceof PyInt ix) { int i = ix.value(); if (i<0) i += list.items().size(); if (i<0||i>=list.items().size()) throw new InterpreterException("IndexError: list index out of range"); stack.push(list.items().get(i)); }
                    else throw new InterpreterException("TypeError: unsupported subscript types");
                }
                case GET_ITER -> { PyObject o = stack.pop(); if (o instanceof PyList list) stack.push(new PyIterator(list.items(), 0)); else throw new InterpreterException("TypeError: '" + o + "' is not iterable"); }
                case FOR_ITER -> { PyObject top = stack.peek(); if (top instanceof PyIterator iter) { if (iter.hasNext()) stack.push(iter.next()); else { stack.pop(); pc = ai; continue; } } else throw new InterpreterException("TypeError: expected iterator"); }
                case LOAD_ATTR -> {
                    String attr = (String) pool[ai];
                    PyObject obj = stack.pop();
                    if (obj instanceof PyModule mod) {
                        PyObject val = mod.attrs().get(attr);
                        if (val == null) throw new InterpreterException("AttributeError: module '" + mod.name() + "' has no attribute '" + attr + "'");
                        stack.push(val);
                    } else throw new InterpreterException("AttributeError: '" + obj + "' has no attribute '" + attr + "'");
                }
                case HALT -> { return; }
            }
            pc++;
        }
    }

    @FunctionalInterface private interface IntBinOp { int apply(int a, int b); }
    private void binaryInt(IntBinOp op) { var r = stack.pop(); var l = stack.pop(); if (l instanceof PyInt li && r instanceof PyInt ri) stack.push(new PyInt(op.apply(li.value(), ri.value()))); else throw new InterpreterException("TypeError: unsupported operand types for arithmetic"); }
    private static boolean isTruthy(PyObject v) { return switch (v) { case PyInt i -> i.value()!=0; case PyStr s -> !s.value().isEmpty(); case PyNull n -> false; case PyFunction f -> true; case PyList l -> !l.items().isEmpty(); case PyIterator it -> true; case PyFloat f -> f.value()!=0.0; case PyModule m -> true; case PyBuiltinFunction bf -> true; }; }

    private void importModule(String name) {
        // Built-in modules take priority over file-based ones
        PyModule builtin = switch (name) {
            case "math" -> BuiltinModules.math();
            case "sys"  -> BuiltinModules.sys();
            case "os"   -> BuiltinModules.os();
            default     -> null;
        };
        if (builtin != null) { globals.put(name, builtin); return; }
        // Fall back to file-based modules in modules/ directory
        try (var br = new BufferedReader(new FileReader("modules/" + name + ".py"))) { String l; while ((l = br.readLine()) != null) if (!l.isBlank()) exec(l); }
        catch (IOException e) { throw new InterpreterException("ImportError: no module named '" + name + "'"); }
    }

    // ─── Bytecode public API ──────────────────────────────────────────────
    public int[] compileToBytecode(String src) { return compile(src.strip()).bytecode(); }
    public CompiledCode compileToCode(String src) { return compile(src.strip()); }

    // ─── Runtime object hierarchy ─────────────────────────────────────────
    public sealed interface PyObject permits PyInt, PyStr, PyNull, PyFunction, PyList, PyIterator, PyModule, PyBuiltinFunction, PyFloat {}
    public record PyInt(int value) implements PyObject { @Override public String toString() { return Integer.toString(value); } }
    public record PyStr(String value) implements PyObject { @Override public String toString() { return value; } }
    public record PyNull() implements PyObject { @Override public String toString() { return "None"; } }
    public record PyFunction(String name, List<String> params, CompiledCode code) implements PyObject { @Override public String toString() { return "<function " + name + ">"; } }
    /** A Python float (64‑bit double). toString() mirrors CPython's repr. */
    public record PyFloat(double value) implements PyObject {
        @Override public String toString() {
            if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
            if (Double.isNaN(value)) return "nan";
            // Format whole numbers as X.0, otherwise use Java's default
            return (value == Math.floor(value)) ? (long) value + ".0" : String.valueOf(value);
        }
    }
    /** A built‑in module (e.g. math, sys, os) – a named attribute dictionary. */
    public record PyModule(String name, Map<String, PyObject> attrs) implements PyObject { @Override public String toString() { return "<module '" + name + "'>"; } }
    /** A Java‑backed callable exposed to Python scripts. */
    public record PyBuiltinFunction(String name, java.util.function.Function<PyObject[], PyObject> fn) implements PyObject { @Override public String toString() { return "<built-in function " + name + ">"; } }
    public record PyList(List<PyObject> items) implements PyObject {
        @Override public String toString() { var sb = new StringBuilder("["); for (int i=0;i<items.size();i++) { if (i>0) sb.append(", "); var it=items.get(i); if (it instanceof PyStr s) sb.append("'").append(s.value()).append("'"); else sb.append(it); } return sb.append("]").toString(); }
    }
    public static final class PyIterator implements PyObject {
        private final List<PyObject> items; private int cursor;
        public PyIterator(List<PyObject> items, int cursor) { this.items = items; this.cursor = cursor; }
        boolean hasNext() { return cursor < items.size(); }
        PyObject next() { return items.get(cursor++); }
        @Override public String toString() { return "<iterator>"; }
    }

    // ─── Error handling ───────────────────────────────────────────────────
    public static class InterpreterException extends RuntimeException { public InterpreterException(String m) { super(m); } }
    private static final class ThrowingErrorListener extends BaseErrorListener {
        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();
        @Override public void syntaxError(Recognizer<?,?> r, Object s, int l, int c, String m, RecognitionException e) { throw new InterpreterException("SyntaxError at "+l+":"+c+" – "+m); }
    }
}
