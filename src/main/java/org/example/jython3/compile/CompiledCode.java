package org.example.jython3.compile;

/**
 * Immutable bytecode unit: one int per instruction, a constants pool, and a
 * parallel line table. {@code lines[i]} is the source line that produced the
 * instruction at {@code bytecode[i]} and is used to build tracebacks.
 * {@code hasYield} is precomputed to avoid scanning bytecode on every function call.
 * {@code attrCache} provides per-instruction monomorphic inline caching for attribute lookups.
 */
public record CompiledCode(int[] bytecode, Object[] constPool, int[] lines, boolean hasYield, Object[] attrCache) {

    public CompiledCode(int[] bytecode, Object[] constPool, int[] lines) {
        this(bytecode, constPool, lines, scanYield(bytecode), new Object[bytecode != null ? bytecode.length : 0]);
    }

    private static boolean scanYield(int[] bytecode) {
        if (bytecode == null) return false;
        int yieldOp = OpCode.YIELD_VALUE.code();
        for (int instr : bytecode) {
            if ((instr >>> 16) == yieldOp) return true;
        }
        return false;
    }

    /** Source line of the instruction at {@code pc}, or 0 when unknown. */
    public int lineAt(int pc) {
        return pc >= 0 && pc < lines.length ? lines[pc] : 0;
    }
}
