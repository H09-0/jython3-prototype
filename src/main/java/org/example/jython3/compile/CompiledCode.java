package org.example.jython3.compile;

/**
 * Immutable bytecode unit: one int per instruction, a constants pool, and a
 * parallel line table. {@code lines[i]} is the source line that produced the
 * instruction at {@code bytecode[i]} and is used to build tracebacks.
 */
public record CompiledCode(int[] bytecode, Object[] constPool, int[] lines) {

    /** Source line of the instruction at {@code pc}, or 0 when unknown. */
    public int lineAt(int pc) {
        return pc >= 0 && pc < lines.length ? lines[pc] : 0;
    }
}
