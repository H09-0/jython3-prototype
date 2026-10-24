package org.example.jython3.compile;

/** Immutable bytecode unit: one int per instruction plus a constants pool. */
public record CompiledCode(int[] bytecode, Object[] constPool) {}
