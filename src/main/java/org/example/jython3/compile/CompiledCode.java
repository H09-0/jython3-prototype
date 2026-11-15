package org.example.jython3.compile;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyCompiledCodeWrapper;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyFunction;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable bytecode unit: one int per instruction, a constants pool, and a
 * parallel line table. {@code lines[i]} is the source line that produced the
 * instruction at {@code bytecode[i]} and is used to build tracebacks.
 * {@code hasYield} is precomputed to avoid scanning bytecode on every function call.
 * {@code attrCache} provides per-instruction monomorphic inline caching for attribute lookups.
 * {@code referencedNames} tracks variable names actually loaded/stored in bytecode (or nested functions)
 * so closures only capture referenced outer bindings and never confuse string literals with variable names.
 * {@code pyConstPool} pre-boxes immutable literal constants once at compile time so
 * {@code LOAD_CONST} avoids per-instruction type dispatch and wrapper allocation.
 */
public record CompiledCode(int[] bytecode, Object[] constPool, int[] lines,
                           boolean hasYield, Object[] attrCache, Set<String> referencedNames,
                           PyObject[] pyConstPool) {

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines) {
        this(bytecode, constPool, lines,
             scanYield(bytecode),
             new Object[bytecode != null ? bytecode.length : 0],
             collectReferencedNames(bytecode, constPool),
             buildPyConstPool(constPool));
    }

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines, boolean hasYield, Object[] attrCache) {
        this(bytecode, constPool, lines, hasYield, attrCache,
             collectReferencedNames(bytecode, constPool),
             buildPyConstPool(constPool));
    }

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines,
                        boolean hasYield, Object[] attrCache, Set<String> referencedNames) {
        this(bytecode, constPool, lines, hasYield, attrCache, referencedNames,
             buildPyConstPool(constPool));
    }

    private static PyObject[] buildPyConstPool(Object[] constPool) {
        if (constPool == null || constPool.length == 0) return new PyObject[0];
        PyObject[] py = new PyObject[constPool.length];
        for (int i = 0; i < constPool.length; i++) {
            Object v = constPool[i];
            if (v == null) {
                py[i] = PyNull.INSTANCE;
            } else if (v instanceof Integer iv) {
                py[i] = PyInt.of(iv);
            } else if (v instanceof Double dv) {
                py[i] = new PyFloat(dv);
            } else if (v instanceof Boolean bv) {
                py[i] = PyBool.of(bv);
            } else if (v instanceof BigInt bi) {
                py[i] = bi;
            } else if (v instanceof String s && !s.startsWith("@global:") && !s.startsWith("@nonlocal:")) {
                py[i] = new PyStr(s);
            } else if (v instanceof CompiledCode cc) {
                py[i] = new PyCompiledCodeWrapper(cc);
            }
            // PyFunction (lambdas need closure check) and @global:/@nonlocal: markers stay null
        }
        return py;
    }

    private static boolean scanYield(int[] bytecode) {
        if (bytecode == null) return false;
        int yieldOp = OpCode.YIELD_VALUE.code();
        int yieldFromOp = OpCode.YIELD_FROM.code();
        for (int instr : bytecode) {
            int op = instr >>> 16;
            if (op == yieldOp || op == yieldFromOp) return true;
        }
        return false;
    }

    private static Set<String> collectReferencedNames(int[] bytecode, Object[] constPool) {
        if (bytecode == null || constPool == null || constPool.length == 0) return Set.of();
        Set<String> names = new HashSet<>();
        int loadFast = OpCode.LOAD_FAST.code();
        int storeFast = OpCode.STORE_FAST.code();
        int deleteFast = OpCode.DELETE_FAST.code();
        int loadConst = OpCode.LOAD_CONST.code();

        for (int instr : bytecode) {
            int op = instr >>> 16;
            int ai = instr & 0xFFFF;
            if (ai < constPool.length) {
                if ((op == loadFast || op == storeFast || op == deleteFast) && constPool[ai] instanceof String varName) {
                    // '$'-prefixed names are compiler temporaries (not valid Python identifiers);
                    // they are frame-private and must never be captured into closures.
                    if (!isSyntheticName(varName)) names.add(varName);
                } else if (op == loadConst && constPool[ai] instanceof String s && s.startsWith("@nonlocal:")) {
                    names.add(s.substring(10));
                }
            }
        }
        for (Object c : constPool) {
            if (c instanceof CompiledCode cc) {
                names.addAll(cc.referencedNames());
            } else if (c instanceof PyFunction fn && fn.code() != null) {
                names.addAll(fn.code().referencedNames());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** Source line of the instruction at {@code pc}, or 0 when unknown. */
    public int lineAt(int pc) {
        return pc >= 0 && pc < lines.length ? lines[pc] : 0;
    }

    /**
     * Compiler-generated temporaries start with '$', a character the lexer never
     * accepts in identifiers, so they cannot collide with user variables.
     */
    public static boolean isSyntheticName(String name) {
        return name != null && !name.isEmpty() && name.charAt(0) == '$';
    }
}
