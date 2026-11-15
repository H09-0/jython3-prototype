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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                           PyObject[] pyConstPool, String[] slotNames) {

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines) {
        this(bytecode, constPool, lines,
             scanYield(bytecode),
             new Object[bytecode != null ? bytecode.length : 0],
             collectReferencedNames(bytecode, constPool),
             buildPyConstPool(constPool),
             null);
    }

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines, boolean hasYield, Object[] attrCache) {
        this(bytecode, constPool, lines, hasYield, attrCache,
             collectReferencedNames(bytecode, constPool),
             buildPyConstPool(constPool),
             null);
    }

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines,
                        boolean hasYield, Object[] attrCache, Set<String> referencedNames) {
        this(bytecode, constPool, lines, hasYield, attrCache, referencedNames,
             buildPyConstPool(constPool),
             null);
    }

    public int slotCount() {
        return slotNames != null ? slotNames.length : 0;
    }

    /**
     * Rewrites pure local {@code LOAD_FAST}/{@code STORE_FAST} instructions into
     * array-indexed {@code LOAD_SLOT}/{@code STORE_SLOT} when the function has no
     * generators, nested functions, {@code global}/{@code nonlocal}, or dynamic
     * scope inspection ({@code vars}/{@code dir}/{@code locals}).
     */
    public CompiledCode optimizeSlots(List<String> params, String argsParam, String kwargsParam) {
        if (hasYield || bytecode == null || constPool == null) return this;
        int opMakeFn = OpCode.MAKE_FUNCTION.code();
        int opMakeFnDef = OpCode.MAKE_FUNCTION_DEFAULTS.code();
        int opBuildCls = OpCode.BUILD_CLASS.code();
        int opLoadConst = OpCode.LOAD_CONST.code();
        int opLoadFast = OpCode.LOAD_FAST.code();
        int opStoreFast = OpCode.STORE_FAST.code();
        int opDeleteFast = OpCode.DELETE_FAST.code();
        int opDeleteSilent = OpCode.DELETE_NAME_SILENT.code();
        int opLoadSlot = OpCode.LOAD_SLOT.code();
        int opStoreSlot = OpCode.STORE_SLOT.code();

        for (int instr : bytecode) {
            int op = instr >>> 16;
            int ai = instr & 0xFFFF;
            if (op == opMakeFn || op == opMakeFnDef || op == opBuildCls
                    || op == opDeleteFast || op == opDeleteSilent) {
                return this;
            }
            if (op == opLoadConst && ai < constPool.length) {
                Object c = constPool[ai];
                if (c instanceof PyFunction) {
                    return this; // lambda / genexpr captures enclosing currentLocals
                }
                if (c instanceof String s && (s.startsWith("@global:") || s.startsWith("@nonlocal:"))) {
                    return this;
                }
            }
            if (op == opLoadFast && ai < constPool.length && constPool[ai] instanceof String name
                    && ("vars".equals(name) || "dir".equals(name) || "locals".equals(name) || "super".equals(name))) {
                return this;
            }
        }

        Map<String, Integer> slotMap = new HashMap<>();
        List<String> orderedNames = new ArrayList<>();
        if (params != null) {
            for (String p : params) {
                if (slotMap.putIfAbsent(p, orderedNames.size()) == null) orderedNames.add(p);
            }
        }
        if (argsParam != null && slotMap.putIfAbsent(argsParam, orderedNames.size()) == null) {
            orderedNames.add(argsParam);
        }
        if (kwargsParam != null && slotMap.putIfAbsent(kwargsParam, orderedNames.size()) == null) {
            orderedNames.add(kwargsParam);
        }
        for (int instr : bytecode) {
            int op = instr >>> 16;
            int ai = instr & 0xFFFF;
            if (op == opStoreFast && ai < constPool.length && constPool[ai] instanceof String name) {
                if (slotMap.putIfAbsent(name, orderedNames.size()) == null) orderedNames.add(name);
            }
        }
        if (orderedNames.isEmpty() || orderedNames.size() > 256) return this;

        int[] rewritten = bytecode.clone();
        for (int i = 0; i < rewritten.length; i++) {
            int instr = rewritten[i];
            int op = instr >>> 16;
            int ai = instr & 0xFFFF;
            if (ai < constPool.length && constPool[ai] instanceof String name) {
                Integer slot = slotMap.get(name);
                if (slot != null) {
                    if (op == opLoadFast) rewritten[i] = (opLoadSlot << 16) | slot;
                    else if (op == opStoreFast) rewritten[i] = (opStoreSlot << 16) | slot;
                }
            }
        }
        return new CompiledCode(rewritten, constPool, lines, hasYield, attrCache,
                referencedNames, pyConstPool, orderedNames.toArray(new String[0]));
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
