package org.example.jython3.runtime;

import org.example.jython3.compile.CompiledCode;
import org.example.jython3.security.ExecutionContext;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lazy Python generator object with explicit frame suspension and lifecycle states.
 *
 * <p>Each generator owns its own operand stack, locals, exception handler stack,
 * active exceptions, and saved {@code pc}. Nothing is executed on creation; each
 * {@link #next()} resumes from {@code pc} until the next {@code YIELD_VALUE},
 * {@code RETURN_VALUE}, or exception. All frame references are released as soon
 * as the generator reaches {@link State#COMPLETED} or {@link State#FAILED}.
 */
public final class PyGenerator implements PyObject {

    public enum State {
        CREATED,
        RUNNING,
        SUSPENDED,
        COMPLETED,
        FAILED
    }

    private final PyFunction fn;
    private final CompiledCode code;
    private final long ownerInterpreterId;
    private final String file;

    private State state = State.CREATED;
    private int pc = 0;
    private int line;
    private FastStack operandStack;
    private Map<String, PyObject> locals;
    private Deque<ExceptHandler> exceptHandlers;
    private Deque<PyObject> activeExceptions;
    private Set<String> globalCells;
    private Map<String, PyObject> moduleGlobals;
    private ExecutionContext creationContext;

    public PyGenerator(PyFunction fn, Map<String, PyObject> boundLocals,
                       long ownerInterpreterId, Map<String, PyObject> moduleGlobals,
                       String file, int line, ExecutionContext creationContext) {
        this.fn = Objects.requireNonNull(fn, "fn");
        this.code = Objects.requireNonNull(fn.code(), "fn.code");
        this.ownerInterpreterId = ownerInterpreterId;
        this.locals = boundLocals != null ? new HashMap<>(boundLocals) : new HashMap<>();
        this.moduleGlobals = moduleGlobals;
        this.file = file != null ? file : "<module>";
        this.line = line;
        this.creationContext = creationContext;
    }

    /** Legacy convenience constructor for callers/tests without explicit interpreter metadata. */
    public PyGenerator(PyFunction fn, Map<String, PyObject> boundLocals) {
        this(fn, boundLocals, -1L, null, "<module>", 0, null);
    }

    public PyFunction fn() { return fn; }
    public CompiledCode code() { return code; }
    public long ownerInterpreterId() { return ownerInterpreterId; }
    public synchronized State state() { return state; }
    public synchronized int pc() { return pc; }
    public synchronized int line() { return line; }
    public String file() { return file; }
    public synchronized FastStack operandStack() { return operandStack; }
    public synchronized Map<String, PyObject> locals() { return locals; }
    public synchronized Deque<ExceptHandler> exceptHandlers() { return exceptHandlers; }
    public synchronized Deque<PyObject> activeExceptions() { return activeExceptions; }
    public synchronized Set<String> globalCells() { return globalCells; }
    public synchronized Map<String, PyObject> moduleGlobals() { return moduleGlobals; }
    public synchronized ExecutionContext creationContext() { return creationContext; }

    public synchronized Map<String, PyObject> localsSnapshot() {
        return locals != null ? Collections.unmodifiableMap(locals) : Collections.emptyMap();
    }

    public synchronized void replaceLocals(Map<String, PyObject> sanitized) {
        if (this.locals != null) {
            this.locals = new HashMap<>(sanitized);
        }
    }

    /**
     * Validates interpreter ownership, lifecycle state, and context cancellation,
     * allocates the initial frame stacks on first entry, and transitions to {@link State#RUNNING}.
     */
    public synchronized void beginStep(long interpreterId) {
        if (ownerInterpreterId != -1L && ownerInterpreterId != interpreterId) {
            throw PyErrors.error("RuntimeError", "generator cannot be resumed on a different interpreter");
        }
        switch (state) {
            case RUNNING -> throw PyErrors.error("ValueError", "generator already executing");
            case COMPLETED, FAILED -> throw PyErrors.error("StopIteration", "");
            case CREATED -> {
                if (creationContext != null && creationContext.isCancelled()) {
                    fail();
                    throw PyErrors.error("KeyboardInterrupt", "Execution cancelled by host");
                }
                operandStack = new FastStack();
                exceptHandlers = new ArrayDeque<>(4);
                activeExceptions = new ArrayDeque<>(2);
                pc = 0;
                state = State.RUNNING;
            }
            case SUSPENDED -> {
                if (creationContext != null && creationContext.isCancelled()) {
                    fail();
                    throw PyErrors.error("KeyboardInterrupt", "Execution cancelled by host");
                }
                state = State.RUNNING;
            }
        }
    }

    /** Saves suspended frame state at {@code YIELD_VALUE} and transitions to {@link State#SUSPENDED}. */
    public synchronized void suspendAt(int nextPc, FastStack stack, Map<String, PyObject> locals,
                                         Deque<ExceptHandler> exceptHandlers, Deque<PyObject> activeExceptions,
                                         Set<String> globalCells, Map<String, PyObject> moduleGlobals, int line) {
        this.pc = nextPc;
        this.operandStack = stack;
        this.locals = locals;
        this.exceptHandlers = exceptHandlers;
        this.activeExceptions = activeExceptions;
        this.globalCells = globalCells;
        this.moduleGlobals = moduleGlobals;
        this.line = line;
        this.state = State.SUSPENDED;
    }

    /** Marks the generator completed and drops all frame references. */
    public synchronized void complete() {
        this.state = State.COMPLETED;
        releaseFrame();
    }

    /** Marks the generator failed and drops all frame references. */
    public synchronized void fail() {
        this.state = State.FAILED;
        releaseFrame();
    }

    private void releaseFrame() {
        if (operandStack != null) operandStack.clear();
        if (exceptHandlers != null) exceptHandlers.clear();
        if (activeExceptions != null) activeExceptions.clear();
        if (locals != null) locals.clear();
        operandStack = null;
        locals = null;
        exceptHandlers = null;
        activeExceptions = null;
        globalCells = null;
        moduleGlobals = null;
        creationContext = null;
    }

    /** Advances this generator lazily to the next yielded value or raises {@code StopIteration}. */
    public PyObject next() {
        PyCaller caller = PyCaller.CURRENT.get();
        if (caller == null) {
            throw PyErrors.error("RuntimeError", "cannot advance generator outside active interpreter");
        }
        return caller.stepGenerator(this);
    }

    @Override
    public String toString() {
        return "<generator object " + fn.name() + ">";
    }
}
