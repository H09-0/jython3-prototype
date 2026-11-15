package org.example.jython3.compile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.Jython3BaseVisitor;
import org.example.jython3.Jython3Parser;
import org.example.jython3.runtime.PyFunction;

public class CompileVisitor extends Jython3BaseVisitor<Void> {
    private final CodeBuilder b;
    private final boolean isFunctionScope;
    private final boolean inAsyncFunction;

    public CompileVisitor(CodeBuilder b) {
        this(b, false, false);
    }

    public CompileVisitor(CodeBuilder b, boolean isFunctionScope) {
        this(b, isFunctionScope, false);
    }

    public CompileVisitor(CodeBuilder b, boolean isFunctionScope, boolean inAsyncFunction) {
        this.b = b;
        this.isFunctionScope = isFunctionScope;
        this.inAsyncFunction = inAsyncFunction;
    }

    // ── Loop context stack for break/continue jump patching ──────────────
    private static class LoopContext {
        final java.util.List<Integer> breakPatches = new java.util.ArrayList<>();
        int continueTarget = -1; // pc of loop-start / FOR_ITER
        int depth;               // loopStack.size() while this loop is active
        boolean isForLoop;       // true if this is a 'for' loop with an iterator on the stack
    }
    private final java.util.Deque<LoopContext> loopStack = new java.util.ArrayDeque<>();

    /**
     * Tracks a {@code try} statement while its parts are compiled, so that
     * {@code break}, {@code continue}, and {@code return} can run any pending
     * {@code finally} bodies and deregister the exception handler.
     */
    private static class TryScope {
        final Jython3Parser.SuiteContext finallySuite;
        final int loopDepth;
        final boolean isWithScope;
        final String asyncExitVar;
        boolean handlerActive = true;
        boolean insideFinally = false;
        /** True while compiling an {@code except} suite or exceptional {@code finally} of this statement. */
        boolean inHandler = false;
        String boundVar = null;

        TryScope(Jython3Parser.SuiteContext finallySuite, int loopDepth) {
            this(finallySuite, loopDepth, false, null);
        }

        TryScope(Jython3Parser.SuiteContext finallySuite, int loopDepth, boolean isWithScope) {
            this(finallySuite, loopDepth, isWithScope, null);
        }

        TryScope(Jython3Parser.SuiteContext finallySuite, int loopDepth, boolean isWithScope, String asyncExitVar) {
            this.finallySuite = finallySuite;
            this.loopDepth = loopDepth;
            this.isWithScope = isWithScope;
            this.asyncExitVar = asyncExitVar;
        }
    }
    private final java.util.Deque<TryScope> tryStack = new java.util.ArrayDeque<>();
    private int returnCounter = 0;

    private boolean hasPendingScopeExit() {
        if (!tryStack.isEmpty()) return true;
        for (LoopContext lc : loopStack) {
            if (lc.isForLoop) return true;
        }
        return false;
    }

    private void popForLoopsAbove(int fromLoopDepth, int downToLoopDepth) {
        for (LoopContext lc : loopStack) {
            if (lc.depth <= fromLoopDepth && lc.depth > downToLoopDepth) {
                if (lc.isForLoop) {
                    b.emit(OpCode.POP_TOP);
                }
            }
        }
    }

    /**
     * Emits the exit path for scopes crossed by a jump. Scopes are visited
     * innermost first: pop any exited inner for-loop iterators, end the
     * active-exception lifetime, deregister the handler, and run the
     * {@code finally} body (or {@code __exit__}). {@code all} is used by
     * {@code return}, which crosses every scope in the function frame.
     */
    private void emitScopeExits(int targetLoopDepth, boolean all) {
        int currentLoopDepth = loopStack.size();
        for (TryScope scope : tryStack) {
            if (!all && scope.loopDepth < targetLoopDepth) break;
            if (currentLoopDepth > scope.loopDepth) {
                popForLoopsAbove(currentLoopDepth, scope.loopDepth);
                currentLoopDepth = scope.loopDepth;
            }
            if (scope.inHandler) {
                if (scope.boundVar != null) {
                    b.emit(OpCode.DELETE_NAME_SILENT, scope.boundVar);
                }
                b.emit(OpCode.END_EXCEPT_HANDLER);
            }
            if (scope.handlerActive) {
                b.emit(OpCode.POP_EXCEPT_HANDLER);
            }
            if (scope.isWithScope && !scope.insideFinally) {
                b.emit(OpCode.END_WITH);
            }
            if (scope.asyncExitVar != null && !scope.insideFinally) {
                b.emit(OpCode.LOAD_FAST, scope.asyncExitVar);
                b.emit(OpCode.LOAD_CONST, null);
                b.emit(OpCode.LOAD_CONST, null);
                b.emit(OpCode.LOAD_CONST, null);
                b.emitRaw(OpCode.CALL_FUNCTION, 3);
                b.emit(OpCode.GET_AWAITABLE);
                b.emit(OpCode.LOAD_CONST, null);
                int exitAw = b.size();
                b.emitRaw(OpCode.AWAIT_FROM, 0);
                b.patch(exitAw, b.size());
                b.emit(OpCode.POP_TOP);
            }
            if (scope.finallySuite != null && !scope.insideFinally) {
                boolean prevInside = scope.insideFinally;
                boolean prevActive = scope.handlerActive;
                boolean prevInHandler = scope.inHandler;
                String prevBound = scope.boundVar;
                scope.insideFinally = true;
                scope.handlerActive = false;
                scope.inHandler = false;
                scope.boundVar = null;
                try {
                    visit(scope.finallySuite);
                } finally {
                    scope.insideFinally = prevInside;
                    scope.handlerActive = prevActive;
                    scope.inHandler = prevInHandler;
                    scope.boundVar = prevBound;
                }
            }
        }
        int finalDepth = all ? 0 : targetLoopDepth;
        if (currentLoopDepth > finalDepth) {
            popForLoopsAbove(currentLoopDepth, finalDepth);
        }
    }

    /** Compiles a finally suite without letting it re-run itself on exit. */
    private void visitFinally(TryScope scope) {
        if (scope.finallySuite == null) return;
        boolean prevInside = scope.insideFinally;
        scope.insideFinally = true;
        try {
            visit(scope.finallySuite);
        } finally {
            scope.insideFinally = prevInside;
        }
    }

    /**
     * Compiles the exceptional finally path where an unhandled exception is on top of stack.
     * Moves the exception from the operand stack onto activeExceptions so the operand stack
     * is clean during finally execution (allowing break/continue/return inside finally to
     * leave a clean operand stack and pop activeExceptions), then re-raises from activeExceptions.
     */
    private void emitExceptionalFinallyAndReraise(TryScope scope) {
        if (scope.finallySuite == null) {
            b.emit(OpCode.RERAISE);
            return;
        }
        b.emit(OpCode.BEGIN_EXCEPT_HANDLER);
        b.emit(OpCode.POP_TOP);
        boolean prevInside = scope.insideFinally;
        boolean prevActive = scope.handlerActive;
        boolean prevInHandler = scope.inHandler;
        String prevBound = scope.boundVar;
        scope.insideFinally = true;
        scope.handlerActive = false;
        scope.inHandler = true;
        scope.boundVar = null;
        try {
            visit(scope.finallySuite);
        } finally {
            scope.insideFinally = prevInside;
            scope.handlerActive = prevActive;
            scope.inHandler = prevInHandler;
            scope.boundVar = prevBound;
        }
        b.emitRaw(OpCode.RERAISE, 1);
    }

    @Override public Void visitStat(Jython3Parser.StatContext c) {
        for (var stmt : c.stmt()) {
            visit(stmt);
        }
        return null;
    }

    @Override public Void visitStmt(Jython3Parser.StmtContext c) {
        return visitChildren(c);
    }

    @Override public Void visitSuite(Jython3Parser.SuiteContext c) {
        if (c.simple_stmt() != null && !c.simple_stmt().isEmpty()) {
            for (var s : c.simple_stmt()) {
                visit(s);
            }
        }
        if (c.stmt() != null && !c.stmt().isEmpty()) {
            for (var s : c.stmt()) {
                visit(s);
            }
        }
        return null;
    }

    @Override public Void visitSimple_stmt(Jython3Parser.Simple_stmtContext c) {
        b.setLine(c.getStart().getLine());
        return visitChildren(c);
    }

    @Override public Void visitCompound_stmt(Jython3Parser.Compound_stmtContext c) {
        b.setLine(c.getStart().getLine());
        return visitChildren(c);
    }

    // ── Unified LValue assignment, unpacking, augmented assignment & del ──────

    private void emitStarItemsAsList(List<Jython3Parser.Star_exprContext> items) {
        b.emitRaw(OpCode.BUILD_LIST, 0);
        for (var se : items) {
            if (se instanceof Jython3Parser.StarItemContext si) {
                visit(si.expr());
                b.emit(OpCode.LIST_EXTEND);
            } else {
                visit(((Jython3Parser.PlainItemContext) se).expr());
                b.emit(OpCode.LIST_COMP_APPEND);
            }
        }
    }

    private static boolean hasStarItem(List<Jython3Parser.Star_exprContext> items) {
        for (var se : items) {
            if (se instanceof Jython3Parser.StarItemContext) return true;
        }
        return false;
    }

    private void emitRhsTuple(Jython3Parser.Rhs_tupleContext rhs) {
        if (rhs instanceof Jython3Parser.RhsTupleManyContext many) {
            var items = many.star_expr();
            if (hasStarItem(items)) {
                emitStarItemsAsList(items);
                b.emit(OpCode.LIST_TO_TUPLE);
            } else {
                for (var se : items) visit(((Jython3Parser.PlainItemContext) se).expr());
                b.emitRaw(OpCode.BUILD_TUPLE, items.size());
            }
        } else if (rhs instanceof Jython3Parser.RhsTupleSingleCommaContext singleComma) {
            var items = List.of(singleComma.star_expr());
            if (hasStarItem(items)) {
                emitStarItemsAsList(items);
                b.emit(OpCode.LIST_TO_TUPLE);
            } else {
                visit(((Jython3Parser.PlainItemContext) singleComma.star_expr()).expr());
                b.emitRaw(OpCode.BUILD_TUPLE, 1);
            }
        } else {
            visit(((Jython3Parser.RhsSingleContext) rhs).expr());
        }
    }

    private void emitStore(LValue target) {
        if (target instanceof LValue.NameTarget nt) {
            b.emit(OpCode.STORE_FAST, nt.name());
        } else if (target instanceof LValue.AttributeTarget at) {
            visit(at.objectExpr());
            b.emit(OpCode.ROT_TWO);
            b.emit(OpCode.STORE_ATTR, at.attribute());
        } else if (target instanceof LValue.SubscriptTarget st) {
            visit(st.containerExpr());
            visit(st.indexExpr());
            b.emit(OpCode.ROT_THREE);
            b.emit(OpCode.STORE_SUBSCR);
        } else if (target instanceof LValue.TupleTarget tt) {
            int starIdx = -1;
            for (int i = 0; i < tt.elements().size(); i++) {
                if (tt.elements().get(i) instanceof LValue.StarTarget) {
                    starIdx = i;
                    break;
                }
            }
            if (starIdx < 0) {
                b.emitRaw(OpCode.UNPACK_SEQUENCE, tt.elements().size());
                for (LValue elem : tt.elements()) {
                    emitStore(elem);
                }
            } else {
                int before = starIdx;
                int after = tt.elements().size() - 1 - starIdx;
                if (before > 255 || after > 255) {
                    throw new CompileError("too many expressions in star-unpacking assignment");
                }
                b.emitRaw(OpCode.UNPACK_EX, (after << 8) | before);
                for (LValue elem : tt.elements()) {
                    emitStore(elem instanceof LValue.StarTarget st ? st.inner() : elem);
                }
            }
        } else if (target instanceof LValue.StarTarget) {
            throw new CompileError("starred assignment target must be in a list or tuple");
        }
    }

    private void emitAugAssign(LValue target, Jython3Parser.ExprContext rhsExpr, OpCode binaryOp) {
        if (target instanceof LValue.NameTarget nt) {
            b.emit(OpCode.LOAD_FAST, nt.name());
            visit(rhsExpr);
            b.emit(binaryOp);
            b.emit(OpCode.STORE_FAST, nt.name());
        } else if (target instanceof LValue.AttributeTarget at) {
            visit(at.objectExpr());                     // [obj] (evaluated once)
            b.emit(OpCode.DUP_TOP);                     // [obj, obj]
            b.emit(OpCode.LOAD_ATTR, at.attribute());   // [obj, old_val]
            visit(rhsExpr);                             // [obj, old_val, rhs]
            b.emit(binaryOp);                           // [obj, new_val]
            b.emit(OpCode.STORE_ATTR, at.attribute());  // []
        } else if (target instanceof LValue.SubscriptTarget st) {
            visit(st.containerExpr());                  // [container] (evaluated once)
            visit(st.indexExpr());                      // [container, key] (evaluated once)
            b.emit(OpCode.DUP_TOP_TWO);                 // [container, key, container, key]
            b.emit(OpCode.BINARY_SUBSCR);               // [container, key, old_val]
            visit(rhsExpr);                             // [container, key, old_val, rhs]
            b.emit(binaryOp);                           // [container, key, new_val]
            b.emit(OpCode.STORE_SUBSCR);                // []
        } else if (target instanceof LValue.TupleTarget) {
            throw new CompileError("illegal target for augmented assignment");
        }
    }

    private void emitDelete(LValue target) {
        if (target instanceof LValue.NameTarget nt) {
            b.emit(OpCode.DELETE_FAST, nt.name());
        } else if (target instanceof LValue.AttributeTarget at) {
            visit(at.objectExpr());
            b.emit(OpCode.DELETE_ATTR, at.attribute());
        } else if (target instanceof LValue.SubscriptTarget st) {
            visit(st.containerExpr());
            visit(st.indexExpr());
            b.emit(OpCode.DELETE_SUBSCR);
        } else if (target instanceof LValue.TupleTarget tt) {
            for (LValue elem : tt.elements()) {
                emitDelete(elem);
            }
        } else if (target instanceof LValue.StarTarget) {
            throw new CompileError("cannot use starred expression here");
        }
    }

    @Override public Void visitAssign_stmt(Jython3Parser.Assign_stmtContext c) {
        if (c.yield_expr() != null) {
            visitYield_expr(c.yield_expr());
        } else {
            emitRhsTuple(c.rhs_tuple());
        }
        var targetLists = c.target_list();
        for (int i = 0; i < targetLists.size(); i++) {
            if (i < targetLists.size() - 1) {
                b.emit(OpCode.DUP_TOP);
            }
            emitStore(LValue.fromTargetList(targetLists.get(i)));
        }
        return null;
    }

    // ── global / nonlocal ─────────────────────────────────────────────────────
    @Override public Void visitGlobal_stmt(Jython3Parser.Global_stmtContext c) {
        // Mark each name as global in this function's scope.
        // The VM resolves LOAD_GLOBAL_NAME / STORE_GLOBAL_NAME at runtime.
        for (var id : c.ID()) b.emit(OpCode.LOAD_CONST, "@global:" + id.getText());
        return null;
    }

    @Override public Void visitNonlocal_stmt(Jython3Parser.Nonlocal_stmtContext c) {
        // nonlocal is handled via closure capture; mark names as cell variables.
        for (var id : c.ID()) b.emit(OpCode.LOAD_CONST, "@nonlocal:" + id.getText());
        return null;
    }

    // ── assert ────────────────────────────────────────────────────────────────
    @Override public Void visitAssert_stmt(Jython3Parser.Assert_stmtContext c) {
        // assert expr [, msg]
        // JUMP_IF_FALSE pops condition. If false -> raise AssertionError; if true -> skip
        visit(c.expr(0));
        int jumpFalse = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);

        // Condition was True: skip over the AssertionError raising
        int jumpOk = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        // Condition was False: construct and throw AssertionError
        b.patch(jumpFalse, b.size());
        if (c.expr().size() > 1) {
            visit(c.expr(1));
            b.emitRaw(OpCode.ASSERT, 1);
        } else {
            b.emitRaw(OpCode.ASSERT, 0);
        }
        b.patch(jumpOk, b.size());
        return null;
    }

    @Override public Void visitAug_assign_stmt(Jython3Parser.Aug_assign_stmtContext c) {
        LValue target = LValue.fromSingleTarget(c.single_target());
        String opText = c.aug_op().getText();
        OpCode op = switch (opText) {
            case "+="  -> OpCode.INPLACE_ADD;
            case "-="  -> OpCode.INPLACE_SUBTRACT;
            case "*="  -> OpCode.INPLACE_MULTIPLY;
            case "/="  -> OpCode.DIVIDE;
            case "//=" -> OpCode.FLOOR_DIVIDE;
            case "%="  -> OpCode.MODULO;
            case "**=" -> OpCode.POWER;
            case "&="  -> OpCode.INPLACE_AND;
            case "|="  -> OpCode.INPLACE_OR;
            case "^="  -> OpCode.INPLACE_XOR;
            case "<<=" -> OpCode.LSHIFT;
            case ">>=" -> OpCode.RSHIFT;
            default    -> throw new CompileError("unknown augmented assignment operator: " + opText);
        };
        emitAugAssign(target, c.expr(), op);
        return null;
    }

    @Override public Void visitImport_stmt(Jython3Parser.Import_stmtContext c) {
        if (c.IMPORT() != null && c.FROM() == null) {
            // import dotted_as_name, ...
            for (var d : c.dotted_as_name()) {
                String modName = d.dotted_name().getText();
                String alias = d.ID() != null ? d.ID().getText() : null;
                b.emit(OpCode.IMPORT_MODULE, alias != null ? modName + ":" + alias : modName);
            }
        } else if (c.FROM() != null) {
            // from dotted_name import ...
            String modName = c.dotted_name().getText();
            if (c.MUL() != null) {
                b.emit(OpCode.IMPORT_MODULE, "from:" + modName + ":*");
            } else {
                for (var it : c.import_as_name()) {
                    String name = it.ID(0).getText();
                    String alias = it.ID().size() > 1 ? it.ID(1).getText() : name;
                    b.emit(OpCode.IMPORT_MODULE, "from:" + modName + ":" + name + ":" + alias);
                }
            }
        }
        return null;
    }

    @Override public Void visitReturn_stmt(Jython3Parser.Return_stmtContext c) {
        if (!isFunctionScope) {
            throw new CompileError("'return' outside function");
        }
        if (c.rhs_tuple() != null) {
            emitRhsTuple(c.rhs_tuple());
        } else {
            b.emit(OpCode.LOAD_CONST, null);
        }
        if (hasPendingScopeExit()) {
            // Prefix with '$' so synthetic compiler temporaries can never collide with Python identifiers
            String retVar = "$ret_val_" + (returnCounter++);
            b.emit(OpCode.STORE_FAST, retVar);
            emitScopeExits(0, true);
            b.emit(OpCode.LOAD_FAST, retVar);
            b.emit(OpCode.DELETE_NAME_SILENT, retVar);
        }
        b.emit(OpCode.RETURN_VALUE);
        return null;
    }

    @Override public Void visitYield_stmt(Jython3Parser.Yield_stmtContext c) {
        visitYield_expr(c.yield_expr());
        b.emit(OpCode.POP_TOP); // statement discards the sent/returned value
        return null;
    }

    @Override public Void visitYield_expr(Jython3Parser.Yield_exprContext c) {
        if (!isFunctionScope) {
            throw new CompileError("'yield' outside function");
        }
        if (inAsyncFunction) {
            throw new CompileError("'yield' inside async function");
        }
        if (c.FROM() != null) {
            visit(c.expr());
            b.emit(OpCode.GET_ITER);
            b.emit(OpCode.LOAD_CONST, null);
            int yfIdx = b.size();
            b.emitRaw(OpCode.YIELD_FROM, 0);
            b.patch(yfIdx, b.size());
            return null;
        }
        if (c.rhs_tuple() != null) {
            emitRhsTuple(c.rhs_tuple());
        } else {
            b.emit(OpCode.LOAD_CONST, null);
        }
        b.emit(OpCode.YIELD_VALUE);
        return null;
    }

    @Override public Void visitParenYieldExpr(Jython3Parser.ParenYieldExprContext c) {
        return visitYield_expr(c.yield_expr());
    }

    @Override public Void visitDel_stmt(Jython3Parser.Del_stmtContext c) {
        emitDelete(LValue.fromTargetList(c.target_list()));
        return null;
    }

    @Override public Void visitPass_stmt(Jython3Parser.Pass_stmtContext c) {
        return null;
    }

    @Override public Void visitExecfile_stmt(Jython3Parser.Execfile_stmtContext c) {
        visit(c.expr());
        b.emit(OpCode.EXECFILE);
        return null;
    }

    /**
     * break — emit a JUMP that will be back-patched to after the enclosing loop.
     * Collects the patch site into the innermost LoopContext.
     */
    @Override public Void visitBreak_stmt(Jython3Parser.Break_stmtContext c) {
        if (loopStack.isEmpty()) {
            throw new CompileError("'break' outside loop");
        }
        LoopContext target = loopStack.peek();
        emitScopeExits(target.depth, false);
        // In a 'for' loop, the PyIterator sits on the stack throughout the body.
        // When breaking, we must pop it so enclosing scopes / outer loops find a clean stack.
        if (target.isForLoop) {
            b.emit(OpCode.POP_TOP);
        }
        int idx = b.size();
        b.emitRaw(OpCode.JUMP, 0); // target patched after loop ends
        target.breakPatches.add(idx);
        return null;
    }

    /**
     * continue — emit a JUMP directly to the loop-start / FOR_ITER pc.
     * The target is set when the enclosing while/for visitor pushes its LoopContext.
     */
    @Override public Void visitContinue_stmt(Jython3Parser.Continue_stmtContext c) {
        if (loopStack.isEmpty()) {
            throw new CompileError("'continue' not properly in loop");
        }
        LoopContext target = loopStack.peek();
        emitScopeExits(target.depth, false);
        int t = target.continueTarget;
        if (t >= 0) b.emitRaw(OpCode.JUMP, t);
        return null;
    }

    @Override public Void visitExpr_stmt(Jython3Parser.Expr_stmtContext c) {
        emitRhsTuple(c.rhs_tuple());
        b.emit(OpCode.POP_TOP);
        return null;
    }

    @Override public Void visitFunc_def(Jython3Parser.Func_defContext c) {
        String name = c.ID().getText();
        compileFunctionDef(name, name, c.param_list(), c.suite(), false);
        return null;
    }

    @Override public Void visitAsync_func_def(Jython3Parser.Async_func_defContext c) {
        String name = c.ID().getText();
        compileFunctionDef(name, name, c.param_list(), c.suite(), true);
        return null;
    }

    private static final class ParsedSignature {
        final List<String> positional = new ArrayList<>();
        int posonlyCount = 0;
        final List<Jython3Parser.ExprContext> defaultExprs = new ArrayList<>();
        String argsParam = null;
        final List<String> kwonlyParams = new ArrayList<>();
        final List<Jython3Parser.ExprContext> kwDefaultExprs = new ArrayList<>();
        int kwDefaultNonNullCount = 0;
        String kwargsParam = null;

        static ParsedSignature parse(Jython3Parser.Param_listContext paramList) {
            var sig = new ParsedSignature();
            if (paramList == null) return sig;
            var seenNames = new java.util.HashSet<String>();
            boolean seenSlash = false;
            boolean seenStar = false;
            boolean seenKwargs = false;
            boolean bareStar = false;

            for (var item : paramList.param_item()) {
                if (seenKwargs) {
                    throw new CompileError("arguments cannot follow var-keyword argument");
                }
                if (item instanceof Jython3Parser.PosOnlySlashParamContext) {
                    if (seenSlash) throw new CompileError("/ may appear only once");
                    if (seenStar) throw new CompileError("/ must be ahead of *");
                    if (sig.positional.isEmpty()) throw new CompileError("at least one argument must precede /");
                    seenSlash = true;
                    sig.posonlyCount = sig.positional.size();
                } else if (item instanceof Jython3Parser.BareStarParamContext) {
                    if (seenStar) throw new CompileError("star argument may appear only once");
                    seenStar = true;
                    bareStar = true;
                } else if (item instanceof Jython3Parser.ArgsParamContext ap) {
                    if (seenStar) throw new CompileError("var-positional argument may appear only once");
                    String name = ap.ID().getText();
                    if (!seenNames.add(name)) throw new CompileError("duplicate argument '" + name + "' in function definition");
                    seenStar = true;
                    sig.argsParam = name;
                } else if (item instanceof Jython3Parser.KwargsParamContext kp) {
                    String name = kp.ID().getText();
                    if (!seenNames.add(name)) throw new CompileError("duplicate argument '" + name + "' in function definition");
                    seenKwargs = true;
                    sig.kwargsParam = name;
                } else if (item instanceof Jython3Parser.SimpleParamContext sp) {
                    String name = sp.ID().getText();
                    if (!seenNames.add(name)) throw new CompileError("duplicate argument '" + name + "' in function definition");
                    if (seenStar) {
                        sig.kwonlyParams.add(name);
                        sig.kwDefaultExprs.add(null);
                    } else {
                        if (!sig.defaultExprs.isEmpty()) {
                            throw new CompileError("non-default argument follows default argument");
                        }
                        sig.positional.add(name);
                    }
                } else if (item instanceof Jython3Parser.DefaultParamContext dp) {
                    String name = dp.ID().getText();
                    if (!seenNames.add(name)) throw new CompileError("duplicate argument '" + name + "' in function definition");
                    if (seenStar) {
                        sig.kwonlyParams.add(name);
                        sig.kwDefaultExprs.add(dp.expr());
                        sig.kwDefaultNonNullCount++;
                    } else {
                        sig.positional.add(name);
                        sig.defaultExprs.add(dp.expr());
                    }
                }
            }
            if (bareStar && sig.kwonlyParams.isEmpty()) {
                throw new CompileError("named arguments must follow bare *");
            }
            return sig;
        }

        org.example.jython3.runtime.PyObject[] buildProtoKwDefaults() {
            var arr = new org.example.jython3.runtime.PyObject[kwonlyParams.size()];
            for (int i = 0; i < kwonlyParams.size(); i++) {
                arr[i] = kwDefaultExprs.get(i) != null ? org.example.jython3.runtime.PyNull.INSTANCE : null;
            }
            return arr;
        }
    }

    private void compileFunctionDef(String fnName,
                                     String storeName,
                                     Jython3Parser.Param_listContext paramList,
                                     Jython3Parser.SuiteContext suite,
                                     boolean isAsync) {
        ParsedSignature sig = ParsedSignature.parse(paramList);
        int totalDefaults = sig.defaultExprs.size() + sig.kwDefaultNonNullCount;
        boolean hasArgs   = sig.argsParam != null;
        boolean hasKwargs = sig.kwargsParam != null;
        int flags = (hasArgs ? 1 : 0) | (hasKwargs ? 2 : 0);

        var fb = new CodeBuilder();
        var fv = new CompileVisitor(fb, true, isAsync);
        fv.visit(suite);
        fb.emit(OpCode.LOAD_CONST, null);
        fb.emit(OpCode.RETURN_VALUE);
        fb.emit(OpCode.HALT);

        CompiledCode fnCode = fb.build()
                .withFunctionLocals(sig.positional, sig.kwonlyParams, sig.argsParam, sig.kwargsParam);
        if (!isAsync) {
            fnCode = fnCode.optimizeSlots(sig.positional, sig.kwonlyParams, sig.argsParam, sig.kwargsParam);
        }
        b.emit(OpCode.LOAD_CONST,
               new PyFunction(fnName, sig.positional, sig.posonlyCount,
                              new org.example.jython3.runtime.PyObject[0],
                              sig.argsParam, sig.kwonlyParams, sig.buildProtoKwDefaults(),
                              sig.kwargsParam, fnCode, null, isAsync));

        if (totalDefaults == 0 && !hasArgs && !hasKwargs) {
            b.emit(OpCode.MAKE_FUNCTION, storeName);
        } else {
            for (var dExpr : sig.defaultExprs) visit(dExpr);
            for (var kwExpr : sig.kwDefaultExprs) {
                if (kwExpr != null) visit(kwExpr);
            }
            b.emitRaw(OpCode.MAKE_FUNCTION_DEFAULTS, (totalDefaults << 2) | flags);
            b.emit(OpCode.MAKE_FUNCTION, storeName);
        }
    }

    @Override public Void visitDecorated_func_def(Jython3Parser.Decorated_func_defContext c) {
        String name;
        Jython3Parser.Param_listContext params;
        Jython3Parser.SuiteContext suite;
        boolean isAsync;
        if (c.func_def() != null) {
            name = c.func_def().ID().getText();
            params = c.func_def().param_list();
            suite = c.func_def().suite();
            isAsync = false;
        } else {
            name = c.async_func_def().ID().getText();
            params = c.async_func_def().param_list();
            suite = c.async_func_def().suite();
            isAsync = true;
        }
        // Evaluate decorators top-to-bottom BEFORE binding 'name' (required for @prop.setter)
        List<Jython3Parser.DecoratorContext> decorators = c.decorator();
        for (int i = 0; i < decorators.size(); i++) {
            visit(decorators.get(i).expr());
        }
        String tempFn = "$dec_fn_" + (compCounter++);
        compileFunctionDef(name, tempFn, params, suite, isAsync);
        b.emit(OpCode.LOAD_FAST, tempFn);
        b.emit(OpCode.DELETE_NAME_SILENT, tempFn);
        for (int i = decorators.size() - 1; i >= 0; i--) {
            b.emitRaw(OpCode.CALL_FUNCTION, 1);
        }
        b.emit(OpCode.STORE_FAST, name);
        return null;
    }

    @Override public Void visitAwaitExpr(Jython3Parser.AwaitExprContext c) {
        if (!inAsyncFunction) {
            throw new CompileError("'await' outside async function");
        }
        visit(c.expr());
        b.emit(OpCode.GET_AWAITABLE);
        b.emit(OpCode.LOAD_CONST, null);
        int afIdx = b.size();
        b.emitRaw(OpCode.AWAIT_FROM, 0);
        b.patch(afIdx, b.size());
        return null;
    }

    @Override public Void visitAsync_for_stmt(Jython3Parser.Async_for_stmtContext c) {
        if (!inAsyncFunction) {
            throw new CompileError("'async for' outside async function");
        }
        visit(c.expr());
        b.emit(OpCode.GET_AITER);
        int loopTarget = b.size();
        int pushHandler = b.size();
        b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);
        b.emit(OpCode.DUP_TOP);
        b.emit(OpCode.LOAD_ATTR, "__anext__");
        b.emitRaw(OpCode.CALL_FUNCTION, 0);
        b.emit(OpCode.GET_AWAITABLE);
        b.emit(OpCode.LOAD_CONST, null);
        int afIdx = b.size();
        b.emitRaw(OpCode.AWAIT_FROM, 0);
        b.patch(afIdx, b.size());
        b.emit(OpCode.POP_EXCEPT_HANDLER);
        emitStore(LValue.fromTargetList(c.target_list()));

        var ctx = new LoopContext();
        ctx.continueTarget = loopTarget;
        ctx.isForLoop = true;
        loopStack.push(ctx);
        ctx.depth = loopStack.size();
        visit(c.suite(0));
        loopStack.pop();
        b.emitRaw(OpCode.JUMP, loopTarget);

        // Exception handler around await it.__anext__()
        b.patch(pushHandler, b.size());
        b.emit(OpCode.LOAD_FAST, "StopAsyncIteration");
        b.emit(OpCode.COMPARE_EXC_TYPE);
        int notStop = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        b.emit(OpCode.POP_TOP); // pop StopAsyncIteration
        b.emit(OpCode.POP_TOP); // pop aiter
        if (c.ELSE() != null && c.suite().size() > 1) {
            visit(c.suite(1));
        }
        int endJump = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        b.patch(notStop, b.size());
        b.emit(OpCode.ROT_TWO);
        b.emit(OpCode.POP_TOP); // drop aiter beneath unexpected exception
        b.emit(OpCode.RERAISE);

        int afterLoop = b.size();
        b.patch(endJump, afterLoop);
        for (int bp : ctx.breakPatches) b.patch(bp, afterLoop);
        return null;
    }

    @Override public Void visitAsync_with_stmt(Jython3Parser.Async_with_stmtContext c) {
        if (!inAsyncFunction) {
            throw new CompileError("'async with' outside async function");
        }
        compileAsyncWithItems(c.with_item(), 0, c.suite());
        return null;
    }

    private void compileAsyncWithItems(List<Jython3Parser.With_itemContext> items, int idx, Jython3Parser.SuiteContext body) {
        if (idx >= items.size()) {
            visit(body);
            return;
        }
        var item = items.get(idx);
        String aexitVar = "$aexit_" + (compCounter++);
        visit(item.expr());
        b.emit(OpCode.DUP_TOP);
        b.emit(OpCode.LOAD_ATTR, "__aexit__");
        b.emit(OpCode.STORE_FAST, aexitVar);
        b.emit(OpCode.LOAD_ATTR, "__aenter__");
        b.emitRaw(OpCode.CALL_FUNCTION, 0);
        b.emit(OpCode.GET_AWAITABLE);
        b.emit(OpCode.LOAD_CONST, null);
        int enterAw = b.size();
        b.emitRaw(OpCode.AWAIT_FROM, 0);
        b.patch(enterAw, b.size());

        if (item.AS() != null && item.ID() != null) {
            b.emit(OpCode.STORE_FAST, item.ID().getText());
        } else {
            b.emit(OpCode.POP_TOP);
        }

        var asyncWithScope = new TryScope(null, loopStack.size(), false, aexitVar);
        tryStack.push(asyncWithScope);
        int pushH = b.size();
        b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);
        compileAsyncWithItems(items, idx + 1, body);
        b.emit(OpCode.POP_EXCEPT_HANDLER);
        asyncWithScope.handlerActive = false;
        tryStack.pop();

        // Normal exit: await __aexit__(None, None, None)
        b.emit(OpCode.LOAD_FAST, aexitVar);
        b.emit(OpCode.LOAD_CONST, null);
        b.emit(OpCode.LOAD_CONST, null);
        b.emit(OpCode.LOAD_CONST, null);
        b.emitRaw(OpCode.CALL_FUNCTION, 3);
        b.emit(OpCode.GET_AWAITABLE);
        b.emit(OpCode.LOAD_CONST, null);
        int exitAw = b.size();
        b.emitRaw(OpCode.AWAIT_FROM, 0);
        b.patch(exitAw, b.size());
        b.emit(OpCode.POP_TOP);
        b.emit(OpCode.DELETE_NAME_SILENT, aexitVar);
        int jumpAfter = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        // Exception path: stack has [exc]
        b.patch(pushH, b.size());
        String excTmp = "$aexc_" + (compCounter++);
        b.emit(OpCode.STORE_FAST, excTmp);
        b.emit(OpCode.LOAD_FAST, aexitVar);
        b.emit(OpCode.LOAD_FAST, excTmp);
        b.emit(OpCode.LOAD_ATTR, "__class__");
        b.emit(OpCode.LOAD_FAST, excTmp);
        b.emit(OpCode.LOAD_FAST, excTmp);
        b.emit(OpCode.LOAD_ATTR, "__traceback__");
        b.emitRaw(OpCode.CALL_FUNCTION, 3);
        b.emit(OpCode.GET_AWAITABLE);
        b.emit(OpCode.LOAD_CONST, null);
        int excExitAw = b.size();
        b.emitRaw(OpCode.AWAIT_FROM, 0);
        b.patch(excExitAw, b.size());
        b.emit(OpCode.DELETE_NAME_SILENT, aexitVar);
        int notSupp = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        b.emit(OpCode.DELETE_NAME_SILENT, excTmp);
        int jumpSuppressed = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        b.patch(notSupp, b.size());
        b.emit(OpCode.LOAD_FAST, excTmp);
        b.emit(OpCode.DELETE_NAME_SILENT, excTmp);
        b.emit(OpCode.RERAISE);

        int after = b.size();
        b.patch(jumpAfter, after);
        b.patch(jumpSuppressed, after);
    }

    @Override public Void visitClass_def(Jython3Parser.Class_defContext c) {
        String className = c.ID().getText();
        compileClassDef(className, c);
        return null;
    }

    private void compileClassDef(String className, Jython3Parser.Class_defContext c) {
        var cb = new CodeBuilder();
        var cv = new CompileVisitor(cb);
        cv.visit(c.suite());
        cb.emit(OpCode.HALT);

        if (c.arg_list() != null) {
            if (c.arg_list().gen_expr() != null) {
                throw new CompileError("invalid generator expression in class bases");
            }
            if (!c.arg_list().arg_item().isEmpty()) {
                var posItems = c.arg_list().arg_item().stream()
                        .filter(it -> it instanceof Jython3Parser.PositionalArgContext)
                        .toList();
                for (var it : posItems) visit(((Jython3Parser.PositionalArgContext) it).expr());
                b.emitRaw(OpCode.BUILD_LIST, posItems.size());
            } else {
                b.emit(OpCode.LOAD_CONST, null);
            }
        } else {
            b.emit(OpCode.LOAD_CONST, null);
        }
        b.emit(OpCode.LOAD_CONST, cb.build());
        b.emit(OpCode.BUILD_CLASS, className);
    }

    @Override public Void visitDecorated_class_def(Jython3Parser.Decorated_class_defContext c) {
        Jython3Parser.Class_defContext classDef = c.class_def();
        String className = classDef.ID().getText();
        compileClassDef(className, classDef);
        List<Jython3Parser.DecoratorContext> decorators = c.decorator();
        for (int i = decorators.size() - 1; i >= 0; i--) {
            visit(decorators.get(i).expr());
            b.emit(OpCode.LOAD_FAST, className);
            b.emitRaw(OpCode.CALL_FUNCTION, 1);
            b.emit(OpCode.STORE_FAST, className);
        }
        return null;
    }

    @Override public Void visitIf_stmt(Jython3Parser.If_stmtContext c) {
        var jumpToEndPatches = new ArrayList<Integer>();

        // Main IF
        visit(c.expr(0));
        int jumpNext = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        visit(c.suite(0));
        int jumpEnd = b.size();
        b.emitRaw(OpCode.JUMP, 0);
        jumpToEndPatches.add(jumpEnd);
        b.patch(jumpNext, b.size());

        // ELIF clauses
        int elifCount = c.ELIF().size();
        for (int i = 0; i < elifCount; i++) {
            visit(c.expr(i + 1));
            jumpNext = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            visit(c.suite(i + 1));
            jumpEnd = b.size();
            b.emitRaw(OpCode.JUMP, 0);
            jumpToEndPatches.add(jumpEnd);
            b.patch(jumpNext, b.size());
        }

        // ELSE clause
        if (c.ELSE() != null) {
            int elseIdx = 1 + elifCount;
            visit(c.suite(elseIdx));
        }

        for (int patchIdx : jumpToEndPatches) {
            b.patch(patchIdx, b.size());
        }
        return null;
    }

    @Override public Void visitWhile_stmt(Jython3Parser.While_stmtContext c) {
        int loopStart = b.size();
        visit(c.expr());
        int jumpFalse = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);

        var ctx = new LoopContext();
        ctx.continueTarget = loopStart;
        ctx.isForLoop = false;
        loopStack.push(ctx);
        ctx.depth = loopStack.size();
        visit(c.suite(0));
        loopStack.pop();

        b.emitRaw(OpCode.JUMP, loopStart);
        b.patch(jumpFalse, b.size());
        if (c.ELSE() != null && c.suite().size() > 1) {
            visit(c.suite(1));
        }
        int afterLoop = b.size();
        for (int bp : ctx.breakPatches) b.patch(bp, afterLoop);
        return null;
    }

    @Override public Void visitFor_stmt(Jython3Parser.For_stmtContext c) {
        visit(c.expr());
        b.emit(OpCode.GET_ITER);
        int loopTarget = b.size();
        int forIter = b.size();
        b.emitRaw(OpCode.FOR_ITER, 0);
        emitStore(LValue.fromTargetList(c.target_list()));

        var ctx = new LoopContext();
        ctx.continueTarget = loopTarget;
        ctx.isForLoop = true;
        loopStack.push(ctx);
        ctx.depth = loopStack.size();
        visit(c.suite(0));
        loopStack.pop();

        b.emitRaw(OpCode.JUMP, loopTarget);
        b.patch(forIter, b.size());
        if (c.ELSE() != null && c.suite().size() > 1) {
            visit(c.suite(1));
        }
        int afterLoop = b.size();
        for (int bp : ctx.breakPatches) b.patch(bp, afterLoop);
        return null;
    }

    @Override public Void visitTry_stmt(Jython3Parser.Try_stmtContext c) {
        /*
         * Bytecode layout:
         *
         *   PUSH_EXCEPT_HANDLER <handler-pc>
         *   <try body>
         *   POP_EXCEPT_HANDLER
         *   <else body>                    ; normal path only
         *   JUMP <shared-finally>
         *
         *   <handler-pc>:                  ; stack top = exception value
         *   for each clause:
         *     <clause type expr>; COMPARE_EXC_TYPE
         *     JUMP_IF_FALSE <next clause>
         *     BEGIN_EXCEPT_HANDLER     ; caught value becomes active
         *     bind/discard value; <clause body>; END_EXCEPT_HANDLER
         *     JUMP <shared-finally>
         *   <exceptional-finally>          ; runs before re-raising
         *   RERAISE
         *
         *   <shared-finally>:
         *   <finally body>                 ; normal path and handled exceptions
         */
        var exceptClauses = c.except_clause();
        var suites = c.suite();
        var finallySuite = c.FINALLY() != null ? suites.get(suites.size() - 1) : null;

        var scope = new TryScope(finallySuite, loopStack.size());
        tryStack.push(scope);

        // --- try body ---
        int pushIdx = b.size();
        b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0); // patched to the handler chain below
        visit(suites.get(0));
        b.emit(OpCode.POP_EXCEPT_HANDLER);
        scope.handlerActive = false;

        // --- else body (runs only when the try body completed normally) ---
        int elsePushIdx = -1;
        if (c.ELSE() != null) {
            int elseOffset = finallySuite != null ? suites.size() - 2 : suites.size() - 1;
            if (elseOffset > 0 && elseOffset < suites.size()) {
                if (finallySuite != null) {
                    elsePushIdx = b.size();
                    b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);
                    scope.handlerActive = true;
                }
                try {
                    visit(suites.get(elseOffset));
                } finally {
                    scope.handlerActive = false;
                }
                if (finallySuite != null) {
                    b.emit(OpCode.POP_EXCEPT_HANDLER);
                }
            }
        }

        int jumpOverIdx = b.size();
        b.emitRaw(OpCode.JUMP, 0); // skip the handler chain

        // --- handler chain ---
        b.patch(pushIdx, b.size());
        var clauseEndPatches = new java.util.ArrayList<Integer>();
        var clauseMatchErrorPatches = new java.util.ArrayList<Integer>();
        for (var clause : exceptClauses) {
            boolean hasType = clause.expr() != null;
            int nextClauseIdx = -1;

            if (hasType) {
                int matchGuardIdx = -1;
                if (finallySuite != null) {
                    matchGuardIdx = b.size();
                    b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);
                    clauseMatchErrorPatches.add(matchGuardIdx);
                }
                // Resolve the clause expression to a real class object. A name
                // that is not an exception type is rejected by the VM.
                visit(clause.expr());
                b.emit(OpCode.COMPARE_EXC_TYPE);
                if (finallySuite != null) {
                    b.emit(OpCode.POP_EXCEPT_HANDLER);
                }
                nextClauseIdx = b.size();
                b.emitRaw(OpCode.JUMP_IF_FALSE, 0); // try the next clause
            }

            // The caught value becomes the active exception for bare raise.
            // The VM records the previously active one so it can be restored.
            b.emit(OpCode.BEGIN_EXCEPT_HANDLER);

            String boundVarName = null;
            if (clause.AS() != null && clause.ID() != null) {
                boundVarName = clause.ID().getText();
                b.emit(OpCode.STORE_FAST, boundVarName);
            } else {
                b.emit(OpCode.POP_TOP);
            }

            // Always register an exception handler around the except clause body so that
            // any exception raised inside the handler deletes the 'as e' binding (PEP 3110),
            // restores the outer active exception, and runs any finally block before re-raising.
            int handlerPushIdx = b.size();
            b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);

            boolean prevActiveScope = scope.handlerActive;
            boolean prevInHandler = scope.inHandler;
            String prevBoundVar = scope.boundVar;
            scope.handlerActive = true;
            scope.inHandler = true;
            scope.boundVar = boundVarName;

            try {
                visit(clause.suite());
            } finally {
                scope.handlerActive = prevActiveScope;
                scope.inHandler = prevInHandler;
                scope.boundVar = prevBoundVar;
            }

            b.emit(OpCode.POP_EXCEPT_HANDLER);

            // In Python 3, 'as e' variable is explicitly deleted at the end of the except block.
            if (boundVarName != null) {
                b.emit(OpCode.DELETE_NAME_SILENT, boundVarName);
            }

            // Natural end of the handler: restore the outer active exception.
            // Jumps out of the suite emit the same instruction themselves.
            b.emit(OpCode.END_EXCEPT_HANDLER);

            int jmpEnd = b.size();
            b.emitRaw(OpCode.JUMP, 0);
            clauseEndPatches.add(jmpEnd);

            // Exceptional path out of this except clause: delete boundVar (PEP 3110),
            // restore outer active exception, run any finally block, then reraise.
            b.patch(handlerPushIdx, b.size());
            if (boundVarName != null) {
                b.emit(OpCode.DELETE_NAME_SILENT, boundVarName);
            }
            b.emit(OpCode.END_EXCEPT_HANDLER);
            emitExceptionalFinallyAndReraise(scope);

            if (hasType) {
                b.patch(nextClauseIdx, b.size());
            }
        }

        // No clause matched (or exception in else / clause match): run finally, then propagate.
        if (!clauseMatchErrorPatches.isEmpty()) {
            int skipStub = b.size();
            b.emitRaw(OpCode.JUMP, 0);
            int matchErrorStub = b.size();
            for (int mp : clauseMatchErrorPatches) {
                b.patch(mp, matchErrorStub);
            }
            // Evaluating except-clause type raised while the original exception was still on
            // the stack; drop the original exception underneath the new one before finally.
            b.emit(OpCode.ROT_TWO);
            b.emit(OpCode.POP_TOP);
            b.patch(skipStub, b.size());
        }
        int unmatchedFinallyPc = b.size();
        if (elsePushIdx >= 0) {
            b.patch(elsePushIdx, unmatchedFinallyPc);
        }
        emitExceptionalFinallyAndReraise(scope);

        // Normal completion and handled exceptions skip the re-raise path.
        b.patch(jumpOverIdx, b.size());
        for (int ep : clauseEndPatches) b.patch(ep, b.size());

        // Shared finally body for the normal path and handled exceptions.
        visitFinally(scope);

        tryStack.pop();
        return null;
    }

    @Override public Void visitExcept_clause(Jython3Parser.Except_clauseContext c) {
        // Handled inside visitTry_stmt — must not be called independently
        return null;
    }

    // ── Expressions ──

    @Override public Void visitPower(Jython3Parser.PowerContext c) {
        visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.POWER); return null;
    }

    @Override public Void visitUnaryMinus(Jython3Parser.UnaryMinusContext c) {
        visit(c.expr()); b.emit(OpCode.UNARY_MINUS); return null;
    }

    @Override public Void visitBitwiseInvert(Jython3Parser.BitwiseInvertContext c) {
        visit(c.expr()); b.emit(OpCode.BITWISE_INVERT); return null;
    }

    @Override public Void visitShiftExpr(Jython3Parser.ShiftExprContext c) {
        visit(c.expr(0));
        visit(c.expr(1));
        b.emit(c.op.getText().equals("<<") ? OpCode.LSHIFT : OpCode.RSHIFT);
        return null;
    }

    @Override public Void visitBitAndExpr(Jython3Parser.BitAndExprContext c) {
        visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.BITWISE_AND); return null;
    }

    @Override public Void visitBitXorExpr(Jython3Parser.BitXorExprContext c) {
        visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.BITWISE_XOR); return null;
    }

    @Override public Void visitBitOrExpr(Jython3Parser.BitOrExprContext c) {
        visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.BITWISE_OR); return null;
    }

    @Override public Void visitNotExpr(Jython3Parser.NotExprContext c) {
        visit(c.expr()); b.emit(OpCode.LOGICAL_NOT); return null;
    }

    @Override public Void visitMulDivMod(Jython3Parser.MulDivModContext c) {
        visit(c.expr(0));
        visit(c.expr(1));
        String opText = c.op.getText();
        switch (opText) {
            case "*"  -> b.emit(OpCode.MULTIPLY);
            case "/"  -> b.emit(OpCode.DIVIDE);
            case "//" -> b.emit(OpCode.FLOOR_DIVIDE);
            case "%"  -> b.emit(OpCode.MODULO);
        }
        return null;
    }

    @Override public Void visitAddSub(Jython3Parser.AddSubContext c) {
        visit(c.expr(0));
        visit(c.expr(1));
        b.emit(c.op.getText().equals("+") ? OpCode.ADD : OpCode.SUBTRACT);
        return null;
    }

    @Override public Void visitComparison(Jython3Parser.ComparisonContext c) {
        return emitComparisonChain(c);
    }

    /**
     * Compiles single and chained comparisons ({@code a < b < c}, {@code 0 == 0 == 0},
     * {@code 1 in [1, 2] == True}, {@code a in b is not None}) with single evaluation of
     * intermediate expressions and short-circuiting on the first {@code False}.
     * Explicitly parenthesized comparisons like {@code (a < b) < c} stay separate
     * {@code ParenContext} nodes.
     */
    private Void emitComparisonChain(Jython3Parser.ExprContext root) {
        var operands = new ArrayList<Jython3Parser.ExprContext>();
        var ops = new ArrayList<OpCode>();
        collectComparisonChain(root, operands, ops);

        if (ops.size() == 1) {
            visit(operands.get(0));
            visit(operands.get(1));
            b.emit(ops.get(0));
            return null;
        }

        visit(operands.get(0));
        var failJumps = new ArrayList<Integer>();
        for (int i = 0; i < ops.size() - 1; i++) {
            visit(operands.get(i + 1));
            // Stack: [..., prev, next] -> [..., next, prev, next]
            b.emit(OpCode.DUP_TOP);
            b.emit(OpCode.ROT_THREE);
            b.emit(OpCode.ROT_TWO);
            b.emit(ops.get(i));
            int failIdx = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            failJumps.add(failIdx);
        }
        visit(operands.get(operands.size() - 1));
        b.emit(ops.get(ops.size() - 1));
        int endJump = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        int failTarget = b.size();
        for (int j : failJumps) b.patch(j, failTarget);
        b.emit(OpCode.POP_TOP); // discard remaining intermediate operand
        b.emit(OpCode.LOAD_CONST, false);
        b.patch(endJump, b.size());
        return null;
    }

    private static void collectComparisonChain(Jython3Parser.ExprContext node,
                                               List<Jython3Parser.ExprContext> operands,
                                               List<OpCode> ops) {
        if (node instanceof Jython3Parser.ComparisonContext c) {
            collectComparisonChain(c.expr(0), operands, ops);
            operands.add(c.expr(1));
            ops.add(mapCmpOp(c.cmp_op()));
            return;
        }
        operands.add(node);
    }

    private static OpCode mapCmpOp(Jython3Parser.Cmp_opContext cop) {
        if (cop.EQ() != null) return OpCode.COMPARE_EQ;
        if (cop.NEQ() != null) return OpCode.COMPARE_NEQ;
        if (cop.LT() != null) return OpCode.COMPARE_LT;
        if (cop.GT() != null) return OpCode.COMPARE_GT;
        if (cop.LTE() != null) return OpCode.COMPARE_LTE;
        if (cop.GTE() != null) return OpCode.COMPARE_GTE;
        if (cop.NOT() != null && cop.IN() != null) return OpCode.COMPARE_NOT_IN;
        if (cop.IN() != null) return OpCode.COMPARE_IN;
        if (cop.IS() != null && cop.NOT() != null) return OpCode.COMPARE_IS_NOT;
        if (cop.IS() != null) return OpCode.COMPARE_IS;
        throw new CompileError("Unknown comparison op: " + cop.getText());
    }

    @Override public Void visitAndExpr(Jython3Parser.AndExprContext c) {
        visit(c.expr(0));
        int jump = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE_OR_POP, 0);
        visit(c.expr(1));
        b.patch(jump, b.size());
        return null;
    }

    @Override public Void visitOrExpr(Jython3Parser.OrExprContext c) {
        visit(c.expr(0));
        int jump = b.size();
        b.emitRaw(OpCode.JUMP_IF_TRUE_OR_POP, 0);
        visit(c.expr(1));
        b.patch(jump, b.size());
        return null;
    }

    @Override public Void visitParen(Jython3Parser.ParenContext c) { visit(c.expr()); return null; }
    @Override public Void visitInt(Jython3Parser.IntContext c) {
        String text = c.INT().getText().replace("_", "");
        int radix = 10;
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
            radix = 16;
        } else if (text.startsWith("0b") || text.startsWith("0B")) {
            text = text.substring(2);
            radix = 2;
        } else if (text.startsWith("0o") || text.startsWith("0O")) {
            text = text.substring(2);
            radix = 8;
        }
        try {
            b.emit(OpCode.LOAD_CONST, Integer.valueOf(text, radix));
        } catch (NumberFormatException nfe) {
            b.emit(OpCode.LOAD_CONST, new org.example.jython3.runtime.BigInt(new java.math.BigInteger(text, radix)));
        }
        return null;
    }
    @Override public Void visitFloat(Jython3Parser.FloatContext c) {
        b.emit(OpCode.LOAD_CONST, Double.valueOf(c.FLOAT().getText().replace("_", "")));
        return null;
    }

    @Override public Void visitWalrusExpr(Jython3Parser.WalrusExprContext c) {
        visit(c.expr());
        b.emit(OpCode.DUP_TOP);
        b.emit(OpCode.STORE_FAST, c.ID().getText());
        return null;
    }
    @Override public Void visitRStr(Jython3Parser.RStrContext c) {
        String raw = c.RSTRING().getText().substring(1);
        String unquoted;
        if (raw.startsWith("'''") || raw.startsWith("\"\"\"")) {
            unquoted = raw.substring(3, raw.length() - 3);
        } else {
            unquoted = raw.substring(1, raw.length() - 1);
        }
        b.emit(OpCode.LOAD_CONST, unquoted);
        return null;
    }

    @Override public Void visitStr(Jython3Parser.StrContext c) {
        String raw = c.STRING().getText();
        if (!raw.isEmpty() && (raw.charAt(0) == 'u' || raw.charAt(0) == 'U')) {
            raw = raw.substring(1);
        }
        String unquoted;
        if (raw.startsWith("'''") || raw.startsWith("\"\"\"")) {
            unquoted = raw.substring(3, raw.length() - 3);
        } else {
            unquoted = raw.substring(1, raw.length() - 1);
        }
        b.emit(OpCode.LOAD_CONST, StringEscapes.unescape(unquoted));
        return null;
    }

    @Override public Void visitBStr(Jython3Parser.BStrContext c) {
        String raw = c.BSTRING().getText().substring(1); // drop b/B
        String unquoted;
        if (raw.startsWith("'''") || raw.startsWith("\"\"\"")) {
            unquoted = raw.substring(3, raw.length() - 3);
        } else {
            unquoted = raw.substring(1, raw.length() - 1);
        }
        byte[] bytes = parseBytesLiteral(unquoted);
        b.emit(OpCode.LOAD_CONST, org.example.jython3.runtime.PyBytes.wrapOwned(bytes));
        return null;
    }

    private static byte[] parseBytesLiteral(String s) {
        var out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) {
                throw new CompileError("bytes can only contain ASCII literal characters");
            }
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '\n' -> {}
                    case '\r' -> { if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++; }
                    case 'n' -> out.write('\n');
                    case 't' -> out.write('\t');
                    case 'r' -> out.write('\r');
                    case 'a' -> out.write(0x07);
                    case 'b' -> out.write('\b');
                    case 'f' -> out.write('\f');
                    case 'v' -> out.write(0x0B);
                    case '\\' -> out.write('\\');
                    case '\'' -> out.write('\'');
                    case '"' -> out.write('"');
                    case 'x' -> {
                        if (i + 2 < s.length()) {
                            int hi = Character.digit(s.charAt(i + 1), 16);
                            int lo = Character.digit(s.charAt(i + 2), 16);
                            if (hi >= 0 && lo >= 0) {
                                out.write((hi << 4) | lo);
                                i += 2;
                                break;
                            }
                        }
                        throw new CompileError("(unicode error) invalid \\x escape in bytes literal");
                    }
                    default -> {
                        if (next >= '0' && next <= '7') {
                            int val = next - '0';
                            int count = 1;
                            while (count < 3 && i + 1 < s.length()) {
                                char oc = s.charAt(i + 1);
                                if (oc < '0' || oc > '7') break;
                                val = (val << 3) | (oc - '0');
                                i++;
                                count++;
                            }
                            out.write(val & 0xFF);
                        } else {
                            out.write('\\');
                            out.write(next & 0xFF);
                        }
                    }
                }
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }
    @Override public Void visitTrueLit(Jython3Parser.TrueLitContext c) { b.emit(OpCode.LOAD_CONST, Boolean.TRUE); return null; }
    @Override public Void visitFalseLit(Jython3Parser.FalseLitContext c) { b.emit(OpCode.LOAD_CONST, Boolean.FALSE); return null; }
    @Override public Void visitNoneLit(Jython3Parser.NoneLitContext c) { b.emit(OpCode.LOAD_CONST, null); return null; }
    @Override public Void visitId(Jython3Parser.IdContext c) { b.emit(OpCode.LOAD_FAST, c.ID().getText()); return null; }

    // ─────────────────────────────────────────────────────────────────────
    // Shared helper: compile a call arg list into positional + keyword args.
    // Returns true if any keyword argument was present (needs CALL_FUNCTION_KW).
    // ─────────────────────────────────────────────────────────────────────
    private record CallArgs(int nPos, List<String> kwNames) {}

    private CallArgs emitArgList(Jython3Parser.Arg_listContext argList) {
        if (argList == null) return new CallArgs(0, List.of());
        int nPos = 0;
        var kwNames = new ArrayList<String>();
        // First pass: positional args (must precede keyword args per Python grammar)
        for (var item : argList.arg_item()) {
            if (item instanceof Jython3Parser.PositionalArgContext pa) {
                if (!kwNames.isEmpty())
                    throw new CompileError("positional argument follows keyword argument");
                visit(pa.expr());
                nPos++;
            } else if (item instanceof Jython3Parser.KeywordArgContext ka) {
                kwNames.add(ka.ID().getText());
                visit(ka.expr());
            }
        }
        return new CallArgs(nPos, kwNames);
    }

    /** Emit a CALL_FUNCTION, CALL_FUNCTION_KW, or CALL_FUNCTION_EX instruction for the given call. */
    private void emitCall(Jython3Parser.Arg_listContext argList) {
        if (argList != null && argList.gen_expr() != null) {
            visit(argList.gen_expr());
            b.emitRaw(OpCode.CALL_FUNCTION, 1);
            return;
        }
        if (argList != null && hasCallSplat(argList)) {
            emitSplatCall(argList);
            return;
        }
        var ca = emitArgList(argList);
        if (ca.kwNames().isEmpty()) {
            b.emitRaw(OpCode.CALL_FUNCTION, ca.nPos());
        } else {
            // Store KwCallInfo (nPos + kwNames) as a single const-pool entry.
            // The opcode arg is the pool index — fits cleanly in 16 bits.
            var info = new KwCallInfo(ca.nPos(), ca.kwNames().toArray(String[]::new));
            b.emit(OpCode.CALL_FUNCTION_KW, info);  // uses emit(op, Object) → addConst → pool index
        }
    }

    private static boolean hasCallSplat(Jython3Parser.Arg_listContext argList) {
        for (var item : argList.arg_item()) {
            if (item instanceof Jython3Parser.SplatArgContext || item instanceof Jython3Parser.SplatKwArgContext) {
                return true;
            }
        }
        return false;
    }

    private void emitSplatCall(Jython3Parser.Arg_listContext argList) {
        boolean hasKw = false;
        boolean seenKwArg = false;
        boolean seenKwUnpack = false;
        var seenKwNames = new java.util.HashSet<String>();
        b.emitRaw(OpCode.BUILD_LIST, 0);
        for (var item : argList.arg_item()) {
            if (item instanceof Jython3Parser.PositionalArgContext pa) {
                if (seenKwArg || seenKwUnpack) {
                    throw new CompileError("positional argument follows keyword argument");
                }
                visit(pa.expr());
                b.emit(OpCode.LIST_COMP_APPEND);
            } else if (item instanceof Jython3Parser.SplatArgContext sa) {
                if (seenKwUnpack) {
                    throw new CompileError("iterable argument unpacking follows keyword argument unpacking");
                }
                visit(sa.expr());
                b.emit(OpCode.LIST_EXTEND);
            } else if (item instanceof Jython3Parser.KeywordArgContext ka) {
                if (!seenKwNames.add(ka.ID().getText())) {
                    throw new CompileError("keyword argument repeated: " + ka.ID().getText());
                }
                hasKw = true;
                seenKwArg = true;
            } else {
                hasKw = true;
                seenKwUnpack = true;
            }
        }
        if (!hasKw) {
            b.emitRaw(OpCode.CALL_FUNCTION_EX, 0);
            return;
        }
        b.emitRaw(OpCode.BUILD_DICT, 0);
        for (var item : argList.arg_item()) {
            if (item instanceof Jython3Parser.KeywordArgContext ka) {
                b.emit(OpCode.DUP_TOP);
                b.emit(OpCode.LOAD_CONST, ka.ID().getText());
                visit(ka.expr());
                b.emit(OpCode.STORE_SUBSCR);
            }
        }
        for (var item : argList.arg_item()) {
            if (item instanceof Jython3Parser.SplatKwArgContext sk) {
                visit(sk.expr());
                b.emitRaw(OpCode.DICT_MERGE, 0);
            }
        }
        b.emitRaw(OpCode.CALL_FUNCTION_EX, 1);
    }

    @Override public Void visitCallExpr(Jython3Parser.CallExprContext c) {
        visit(c.expr());
        emitCall(c.arg_list());
        return null;
    }

    @Override public Void visitListLiteral(Jython3Parser.ListLiteralContext c) {
        if (c.expr_list() == null) {
            b.emitRaw(OpCode.BUILD_LIST, 0);
            return null;
        }
        var items = c.expr_list().star_expr();
        if (hasStarItem(items)) {
            emitStarItemsAsList(items);
        } else {
            for (var se : items) visit(((Jython3Parser.PlainItemContext) se).expr());
            b.emitRaw(OpCode.BUILD_LIST, items.size());
        }
        return null;
    }

    @Override public Void visitTupleLiteral(Jython3Parser.TupleLiteralContext c) {
        if (c.expr_list() == null) {
            b.emitRaw(OpCode.BUILD_TUPLE, 0);
            return null;
        }
        var items = c.expr_list().star_expr();
        if (items.size() == 1 && c.expr_list().COMMA().isEmpty()) {
            throw new CompileError("cannot use starred expression here");
        }
        if (hasStarItem(items)) {
            emitStarItemsAsList(items);
            b.emit(OpCode.LIST_TO_TUPLE);
        } else {
            for (var se : items) visit(((Jython3Parser.PlainItemContext) se).expr());
            b.emitRaw(OpCode.BUILD_TUPLE, items.size());
        }
        return null;
    }

    @Override public Void visitDictLiteral(Jython3Parser.DictLiteralContext c) {
        if (c.dict_entries() == null) {
            b.emitRaw(OpCode.BUILD_DICT, 0);
            return null;
        }
        var entries = c.dict_entries().dict_entry();
        boolean hasUnpack = false;
        for (var entry : entries) {
            if (entry instanceof Jython3Parser.DictUnpackEntryContext) {
                hasUnpack = true;
                break;
            }
        }
        if (!hasUnpack) {
            for (var entry : entries) {
                var kv = (Jython3Parser.DictKvEntryContext) entry;
                visit(kv.expr(0));
                visit(kv.expr(1));
            }
            b.emitRaw(OpCode.BUILD_DICT, entries.size());
        } else {
            b.emitRaw(OpCode.BUILD_DICT, 0);
            for (var entry : entries) {
                if (entry instanceof Jython3Parser.DictKvEntryContext kv) {
                    b.emit(OpCode.DUP_TOP);
                    visit(kv.expr(0));
                    visit(kv.expr(1));
                    b.emit(OpCode.STORE_SUBSCR);
                } else {
                    var du = (Jython3Parser.DictUnpackEntryContext) entry;
                    visit(du.expr());
                    b.emitRaw(OpCode.DICT_MERGE, 1);
                }
            }
        }
        return null;
    }

    @Override public Void visitSubscript(Jython3Parser.SubscriptContext c) {
        visit(c.expr());
        visit(c.slice_item());
        b.emit(OpCode.BINARY_SUBSCR);
        return null;
    }

    @Override public Void visitSliceSingleIndex(Jython3Parser.SliceSingleIndexContext c) {
        visit(c.expr());
        return null;
    }

    @Override public Void visitSliceDoubleColonStep(Jython3Parser.SliceDoubleColonStepContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        visit(c.expr());                 // step
        b.emitRaw(OpCode.BUILD_SLICE, 3);
        return null;
    }

    @Override public Void visitSliceOmittedStartStep(Jython3Parser.SliceOmittedStartStepContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        visit(c.expr(0));                // stop
        if (c.expr().size() > 1) {
            visit(c.expr(1));            // step
        } else {
            b.emit(OpCode.LOAD_CONST, null); // step omitted
        }
        b.emitRaw(OpCode.BUILD_SLICE, 3);
        return null;
    }

    @Override public Void visitSliceOmittedStart(Jython3Parser.SliceOmittedStartContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        visit(c.expr());                 // stop
        b.emitRaw(OpCode.BUILD_SLICE, 2);
        return null;
    }

    @Override public Void visitSliceOmittedAll(Jython3Parser.SliceOmittedAllContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        b.emitRaw(OpCode.BUILD_SLICE, 2);
        return null;
    }

    @Override public Void visitSliceStartDoubleColonStep(Jython3Parser.SliceStartDoubleColonStepContext c) {
        visit(c.expr(0));                // start
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        if (c.expr().size() > 1) {
            visit(c.expr(1));            // step
        } else {
            b.emit(OpCode.LOAD_CONST, null); // step omitted
        }
        b.emitRaw(OpCode.BUILD_SLICE, 3);
        return null;
    }

    @Override public Void visitSliceFull(Jython3Parser.SliceFullContext c) {
        visit(c.expr(0)); // start
        visit(c.expr(1)); // stop
        if (c.COLON().size() > 1) {
            if (c.expr().size() > 2) {
                visit(c.expr(2)); // step
            } else {
                b.emit(OpCode.LOAD_CONST, null); // step omitted
            }
            b.emitRaw(OpCode.BUILD_SLICE, 3);
        } else {
            b.emitRaw(OpCode.BUILD_SLICE, 2);
        }
        return null;
    }

    @Override public Void visitSliceStartOnly(Jython3Parser.SliceStartOnlyContext c) {
        visit(c.expr());                 // start
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        b.emitRaw(OpCode.BUILD_SLICE, 2);
        return null;
    }

    @Override public Void visitAttrAccess(Jython3Parser.AttrAccessContext c) {
        visit(c.expr());
        b.emit(OpCode.LOAD_ATTR, c.ID().getText());
        return null;
    }

    @Override public Void visitRaise_stmt(Jython3Parser.Raise_stmtContext c) {
        if (c.expr().isEmpty()) {
            // Bare raise: re-raise whatever handler is active.
            b.emit(OpCode.RAISE_ACTIVE);
            return null;
        }
        // The expression may be a class (raise E), a call (raise E("msg")), or
        // an existing exception value (raise e). The VM decides which.
        visit(c.expr(0));
        if (c.FROM() != null) {
            // raise E from C: explicit __cause__ (C may be None to suppress context)
            visit(c.expr(1));
            b.emit(OpCode.RAISE_FROM);
            return null;
        }
        b.emit(OpCode.RAISE);
        return null;
    }

    // ── with statement ──────────────────────────────────────────────────────
    /**
     * Compiles:  with CM [as VAR]: suite
     *
     * Bytecode layout:
     *   LOAD cm_expr
     *   SETUP_WITH              ; calls __enter__; pushes (exit_fn, entered_value)
     *   [STORE_FAST var | POP_TOP]
     *   PUSH_EXCEPT_HANDLER  handler
     *   <suite>
     *   POP_EXCEPT_HANDLER
     *   END_WITH                ; calls exit_fn(None, None, None)
     *   JUMP after
     *
     *  handler:
     *   WITH_EXCEPT_EXIT        ; calls exit_fn(type, value, tb); pushes bool
     *   JUMP_IF_TRUE_OR_POP    after   ; True = exception suppressed
     *   RERAISE
     *
     *  after:
     */
    @Override public Void visitWith_stmt(Jython3Parser.With_stmtContext c) {
        compileWithItems(c.with_item(), 0, c.suite());
        return null;
    }

    private void compileWithItems(List<Jython3Parser.With_itemContext> items, int idx, Jython3Parser.SuiteContext suite) {
        var item = items.get(idx);
        visit(item.expr());
        b.emit(OpCode.SETUP_WITH);         // calls __enter__; stack: [exit_fn, value]

        // Bind or discard the __enter__ result
        if (item.AS() != null) {
            b.emit(OpCode.STORE_FAST, item.ID().getText());
        } else {
            b.emit(OpCode.POP_TOP);        // discard __enter__ value
        }

        // Register exception handler so we can call __exit__ on error
        var withScope = new TryScope(null, loopStack.size(), true);
        tryStack.push(withScope);
        int pushIdx = b.size();
        b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);

        if (idx + 1 < items.size()) {
            compileWithItems(items, idx + 1, suite);
        } else {
            visit(suite);
        }

        b.emit(OpCode.POP_EXCEPT_HANDLER);
        withScope.handlerActive = false;
        tryStack.pop();
        b.emit(OpCode.END_WITH);           // normal exit: calls __exit__(None,None,None)
        int jumpAfter = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        // Exception handler path
        b.patch(pushIdx, b.size());
        b.emit(OpCode.WITH_EXCEPT_EXIT);   // calls __exit__(type,val,tb); pushes True or [exc, False]
        int suppressJump = b.size();
        b.emitRaw(OpCode.JUMP_IF_TRUE_OR_POP, 0); // suppress if __exit__ returned True
        b.emit(OpCode.RERAISE);
        // Suppressed path still has True on TOS; pop it so stack depth matches normal exit.
        b.patch(suppressJump, b.size());
        b.emit(OpCode.POP_TOP);

        b.patch(jumpAfter, b.size());
    }

    // ── generator expressions ──────────────────────────────────────────────────
    @Override public Void visitGenExpr(Jython3Parser.GenExprContext c) {
        return visitGen_expr(c.gen_expr());
    }

    @Override public Void visitGen_expr(Jython3Parser.Gen_exprContext c) {
        // Compile body as an independent generator function `<genexpr>($gen_iter)`:
        // the outermost iterable is evaluated ONCE in the enclosing scope and turned into
        // an iterator via GET_ITER at generator creation time, without consuming elements.
        var gb = new CodeBuilder();
        var gv = new CompileVisitor(gb, true);
        emitGenCompClauses(gv, gb, c.expr(), c.comp_for(), 0, true);
        gb.emit(OpCode.LOAD_CONST, null);
        gb.emit(OpCode.RETURN_VALUE);
        gb.emit(OpCode.HALT);

        var proto = new PyFunction("<genexpr>", List.of("$gen_iter"),
                new org.example.jython3.runtime.PyObject[0], null, null, gb.build(), null);
        b.emit(OpCode.LOAD_CONST, proto);
        visit(c.comp_for(0).expr());
        b.emit(OpCode.GET_ITER);
        b.emitRaw(OpCode.CALL_FUNCTION, 1);
        return null;
    }

    private static void emitGenCompClauses(CompileVisitor gv, CodeBuilder gb,
                                           Jython3Parser.ExprContext yieldExpr,
                                           List<Jython3Parser.Comp_forContext> compFors,
                                           int idx, boolean alreadyIterator) {
        var cf = compFors.get(idx);
        if (alreadyIterator) {
            gb.emit(OpCode.LOAD_FAST, "$gen_iter");
        } else {
            gv.visit(cf.expr());
            gb.emit(OpCode.GET_ITER);
        }
        int loopTop = gb.size();
        int forIter = gb.size();
        gb.emitRaw(OpCode.FOR_ITER, 0);
        gv.emitStore(LValue.fromTargetList(cf.target_list()));

        var filterJumps = new ArrayList<Integer>();
        for (var ci : cf.comp_if()) {
            gv.visit(ci.expr());
            int fj = gb.size();
            gb.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            filterJumps.add(fj);
        }

        if (idx + 1 < compFors.size()) {
            emitGenCompClauses(gv, gb, yieldExpr, compFors, idx + 1, false);
        } else {
            gv.visit(yieldExpr);
            gb.emit(OpCode.YIELD_VALUE);
            gb.emit(OpCode.POP_TOP);
        }

        for (int fj : filterJumps) {
            gb.patch(fj, loopTop);
        }
        gb.emitRaw(OpCode.JUMP, loopTop);
        gb.patch(forIter, gb.size());
    }

    // ── lambda ────────────────────────────────────────────────────────────────
    @Override public Void visitLambdaExpr(Jython3Parser.LambdaExprContext c) {
        // lambda [params]: body_expr
        // Compiled identically to a named anonymous function.
        // The body is a single expression that is returned.
        compileLambda(c.param_list(), c.expr());
        return null;
    }

    /** Compile a lambda expression into an anonymous PyFunction. */
    private void compileLambda(Jython3Parser.Param_listContext paramList,
                               Jython3Parser.ExprContext bodyExpr) {
        ParsedSignature sig = ParsedSignature.parse(paramList);
        int totalDefaults = sig.defaultExprs.size() + sig.kwDefaultNonNullCount;
        boolean hasArgs   = sig.argsParam != null;
        boolean hasKwargs = sig.kwargsParam != null;

        var fb = new CodeBuilder();
        var fv = new CompileVisitor(fb, true);
        fv.visit(bodyExpr);
        fb.emit(OpCode.RETURN_VALUE);
        fb.emit(OpCode.HALT);
        CompiledCode fnCode = fb.build()
                .withFunctionLocals(sig.positional, sig.kwonlyParams, sig.argsParam, sig.kwargsParam);
        b.emit(OpCode.LOAD_CONST,
               new PyFunction("<lambda>", sig.positional, sig.posonlyCount,
                              new org.example.jython3.runtime.PyObject[0],
                              sig.argsParam, sig.kwonlyParams, sig.buildProtoKwDefaults(),
                              sig.kwargsParam, fnCode, null, false));
        if (totalDefaults > 0 || hasArgs || hasKwargs) {
            for (var dExpr : sig.defaultExprs) visit(dExpr);
            for (var kwExpr : sig.kwDefaultExprs) {
                if (kwExpr != null) visit(kwExpr);
            }
            b.emitRaw(OpCode.MAKE_FUNCTION_DEFAULTS, (totalDefaults << 2) | ((hasArgs ? 1 : 0) | (hasKwargs ? 2 : 0)));
        }
    }

    // ── ternary / conditional expression ─────────────────────────────────
    @Override public Void visitTernaryExpr(Jython3Parser.TernaryExprContext c) {
        // expr0 if expr1 else expr2
        visit(c.expr(1));                // condition
        int jumpFalse = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        visit(c.expr(0));                // true branch
        int jumpEnd = b.size();
        b.emitRaw(OpCode.JUMP, 0);
        b.patch(jumpFalse, b.size());
        visit(c.expr(2));                // false branch
        b.patch(jumpEnd, b.size());
        return null;
    }

    // ── unary plus ──────────────────────────────────────────────────────────────
    @Override public Void visitUnaryPlus(Jython3Parser.UnaryPlusContext c) {
        visit(c.expr()); // +x is a no-op for int/float; value unchanged
        return null;
    }

    // ── f-strings ───────────────────────────────────────────────────────────────
    /**
     * Compiles f"...{expr}..." literals.
     * Strategy: split the raw token at { } boundaries, emit each part as a
     * string concatenation so the VM builds the final string at runtime.
     * This avoids a dedicated opcode and reuses existing ADD infrastructure.
     */
    @Override public Void visitFStr(Jython3Parser.FStrContext c) {
        String raw = c.FSTRING().getText();
        String body = raw.substring(1); // drop f/F
        String inner;
        if (body.startsWith("\"\"\"") || body.startsWith("'''")) {
            inner = body.substring(3, body.length() - 3);
        } else {
            inner = body.substring(1, body.length() - 1);
        }
        var parts = splitFString(inner);
        if (parts.isEmpty()) {
            b.emit(OpCode.LOAD_CONST, "");
            return null;
        }
        boolean first = true;
        for (var part : parts) {
            if (part.isExpr()) {
                var parsed = parseFStringExpr(part.text());
                var lexer = new org.example.jython3.Jython3Lexer(
                        org.antlr.v4.runtime.CharStreams.fromString(parsed.expr()));
                lexer.removeErrorListeners();
                var tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
                var parser = new org.example.jython3.Jython3Parser(tokens);
                parser.removeErrorListeners();
                var tree = parser.expr();
                if (parser.getNumberOfSyntaxErrors() > 0 || tokens.LA(1) != org.antlr.v4.runtime.Token.EOF) {
                    throw new CompileError("f-string: invalid expression '" + parsed.expr() + "'");
                }
                b.emit(OpCode.LOAD_FAST, "format");
                if (parsed.conversion() == 'r') {
                    b.emit(OpCode.LOAD_FAST, "repr");
                    visit(tree);
                    b.emitRaw(OpCode.CALL_FUNCTION, 1);
                } else if (parsed.conversion() == 's') {
                    b.emit(OpCode.LOAD_FAST, "str");
                    visit(tree);
                    b.emitRaw(OpCode.CALL_FUNCTION, 1);
                } else {
                    visit(tree);
                }
                b.emit(OpCode.LOAD_CONST, parsed.spec());
                b.emitRaw(OpCode.CALL_FUNCTION, 2);
            } else {
                b.emit(OpCode.LOAD_CONST, StringEscapes.unescape(part.text()));
            }
            if (!first) b.emit(OpCode.ADD);
            first = false;
        }
        return null;
    }

    private record ParsedFExpr(String expr, char conversion, String spec) {}

    private static ParsedFExpr parseFStringExpr(String raw) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        char inQuote = 0;
        int colonIdx = -1;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inQuote != 0) {
                if (ch == '\\') { i++; continue; }
                if (ch == inQuote) inQuote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"') { inQuote = ch; continue; }
            if (ch == '(') parenDepth++;
            else if (ch == ')' && parenDepth > 0) parenDepth--;
            else if (ch == '[') bracketDepth++;
            else if (ch == ']' && bracketDepth > 0) bracketDepth--;
            else if (ch == '{') braceDepth++;
            else if (ch == '}' && braceDepth > 0) braceDepth--;
            else if (ch == ':' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                colonIdx = i;
                break;
            }
        }
        String exprPart = colonIdx >= 0 ? raw.substring(0, colonIdx).trim() : raw.trim();
        String specPart = colonIdx >= 0 ? raw.substring(colonIdx + 1) : "";
        char conv = 0;
        if (exprPart.length() >= 3 && exprPart.charAt(exprPart.length() - 2) == '!') {
            char c = exprPart.charAt(exprPart.length() - 1);
            if (c == 'r' || c == 's' || c == 'a') {
                conv = c;
                exprPart = exprPart.substring(0, exprPart.length() - 2).trim();
            }
        }
        return new ParsedFExpr(exprPart, conv, specPart);
    }

    /** One segment of an f-string: either a literal or an {expr}. */
    private record FStringPart(String text, boolean isExpr) {}

    /** Split the interior of an f-string into alternating literal / expr parts. */
    private java.util.List<FStringPart> splitFString(String inner) {
        var parts = new ArrayList<FStringPart>();
        var sb = new StringBuilder();
        int i = 0;
        while (i < inner.length()) {
            char ch = inner.charAt(i);
            if (ch == '{') {
                if (i + 1 < inner.length() && inner.charAt(i + 1) == '{') {
                    sb.append('{'); i += 2; continue; // {{ escape
                }
                if (sb.length() > 0) { parts.add(new FStringPart(sb.toString(), false)); sb.setLength(0); }
                int depth = 1; i++;
                var exprBuf = new StringBuilder();
                while (i < inner.length() && depth > 0) {
                    char ec = inner.charAt(i);
                    if (ec == '{') depth++;
                    else if (ec == '}') { if (--depth == 0) break; }
                    exprBuf.append(ec); i++;
                }
                i++; // skip closing }
                parts.add(new FStringPart(exprBuf.toString().trim(), true));
            } else if (ch == '}' && i + 1 < inner.length() && inner.charAt(i + 1) == '}') {
                sb.append('}'); i += 2;
            } else {
                sb.append(ch); i++;
            }
        }
        if (sb.length() > 0) parts.add(new FStringPart(sb.toString(), false));
        return parts;
    }

    // ── comprehensions ────────────────────────────────────────────────────────────
    // ── Unique counter for inline comprehension temporary accumulators ────
    private int compCounter = 0;

    /**
     * List comprehension:  [expr FOR id IN iterable (IF cond)?]
     *
     * Clean variable-based compilation (avoids stack-depth juggling):
     *   acc = []
     *   for id in iterable:
     *       if cond:
     *           acc.append(expr)
     *   TOS = acc
     */
    @Override public Void visitListComp(Jython3Parser.ListCompContext c) {
        String accVar = "$comp_list_" + (compCounter++);
        b.emitRaw(OpCode.BUILD_LIST, 0);
        b.emit(OpCode.STORE_FAST, accVar);
        emitComprehensionClauses(c.comp_for(), 0, () -> {
            b.emit(OpCode.LOAD_FAST, accVar);
            visit(c.expr());
            b.emit(OpCode.LIST_COMP_APPEND);
            b.emit(OpCode.POP_TOP);
        });
        b.emit(OpCode.LOAD_FAST, accVar);
        b.emit(OpCode.DELETE_NAME_SILENT, accVar);
        return null;
    }

    @Override public Void visitDictComp(Jython3Parser.DictCompContext c) {
        String accVar = "$comp_dict_" + (compCounter++);
        b.emitRaw(OpCode.BUILD_DICT, 0);
        b.emit(OpCode.STORE_FAST, accVar);
        emitComprehensionClauses(c.comp_for(), 0, () -> {
            b.emit(OpCode.LOAD_FAST, accVar);
            visit(c.expr(0));
            visit(c.expr(1));
            b.emit(OpCode.STORE_SUBSCR);
        });
        b.emit(OpCode.LOAD_FAST, accVar);
        b.emit(OpCode.DELETE_NAME_SILENT, accVar);
        return null;
    }

    @Override public Void visitSetComp(Jython3Parser.SetCompContext c) {
        String accVar = "$comp_set_" + (compCounter++);
        b.emitRaw(OpCode.BUILD_SET, 0);
        b.emit(OpCode.STORE_FAST, accVar);
        emitComprehensionClauses(c.comp_for(), 0, () -> {
            b.emit(OpCode.LOAD_FAST, accVar);
            visit(c.expr());
            b.emit(OpCode.SET_COMP_ADD);
            b.emit(OpCode.POP_TOP);
        });
        b.emit(OpCode.LOAD_FAST, accVar);
        b.emit(OpCode.DELETE_NAME_SILENT, accVar);
        return null;
    }

    private void emitComprehensionClauses(List<Jython3Parser.Comp_forContext> clauses, int idx, Runnable emitBody) {
        if (idx >= clauses.size()) {
            emitBody.run();
            return;
        }
        var cf = clauses.get(idx);
        visit(cf.expr());
        b.emit(OpCode.GET_ITER);
        int loopTop = b.size();
        int forIter = b.size();
        b.emitRaw(OpCode.FOR_ITER, 0);
        emitStore(LValue.fromTargetList(cf.target_list()));

        var skipPatches = new ArrayList<Integer>();
        for (var cif : cf.comp_if()) {
            visit(cif.expr());
            int jp = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            skipPatches.add(jp);
        }

        emitComprehensionClauses(clauses, idx + 1, emitBody);

        int continuePc = b.size();
        for (int sp : skipPatches) {
            b.patch(sp, continuePc);
        }
        b.emitRaw(OpCode.JUMP, loopTop);
        b.patch(forIter, b.size());
    }

    /** Set literal: {expr, expr, ...} */
    @Override public Void visitSetLiteral(Jython3Parser.SetLiteralContext c) {
        var items = c.expr_list().star_expr();
        if (hasStarItem(items)) {
            emitStarItemsAsList(items);
            b.emit(OpCode.LIST_TO_SET);
        } else {
            for (var se : items) visit(((Jython3Parser.PlainItemContext) se).expr());
            b.emitRaw(OpCode.BUILD_SET, items.size());
        }
        return null;
    }
}
