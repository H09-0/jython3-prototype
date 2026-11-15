package org.example.jython3.compile;

import org.example.jython3.runtime.PyFunction;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable bytecode unit: one int per instruction, a constants pool, and a
 * parallel line table. {@code lines[i]} is the source line that produced the
 * instruction at {@code bytecode[i]} and is used to build tracebacks.
 * {@code hasYield} is precomputed to avoid scanning bytecode on every function call.
 * {@code attrCache} provides per-instruction monomorphic inline caching for attribute lookups.
 * {@code referencedNames} tracks symbol names referenced in this code or nested code blocks
 * so closures only capture referenced outer bindings.
 */
public record CompiledCode(int[] bytecode, Object[] constPool, int[] lines,
                           boolean hasYield, Object[] attrCache, Set<String> referencedNames) {

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines) {
        this(bytecode, constPool, lines,
             scanYield(bytecode),
             new Object[bytecode != null ? bytecode.length : 0],
             collectReferencedNames(constPool));
    }

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines, boolean hasYield, Object[] attrCache) {
        this(bytecode, constPool, lines, hasYield, attrCache, collectReferencedNames(constPool));
    }

    private static boolean scanYield(int[] bytecode) {
        if (bytecode == null) return false;
        int yieldOp = OpCode.YIELD_VALUE.code();
        for (int instr : bytecode) {
            if ((instr >>> 16) == yieldOp) return true;
        }
        return false;
    }

    private static Set<String> collectReferencedNames(Object[] constPool) {
        if (constPool == null || constPool.length == 0) return Set.of();
        Set<String> names = new HashSet<>();
        for (Object c : constPool) {
            if (c instanceof String s) {
                if (s.startsWith("@nonlocal:")) {
                    names.add(s.substring(10));
                } else if (!s.startsWith("@global:")) {
                    names.add(s);
                }
            } else if (c instanceof CompiledCode cc) {
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
}
