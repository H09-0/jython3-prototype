package org.example.jython3.compile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CodeBuilder {
    static final int NO_ARG = 0xFFFF;
    private final List<Integer> bytecode = new ArrayList<>();
    private final List<Integer> lines = new ArrayList<>();
    private final List<Object> constPool = new ArrayList<>();
    private final Map<Object, Integer> constMap = new HashMap<>();
    /** Source line attached to instructions emitted from now on. */
    private int currentLine = 0;

    /** Sets the source line recorded for subsequent instructions. */
    public void setLine(int line) {
        if (line > 0) currentLine = line;
    }

    public int currentLine() { return currentLine; }

    private void add(int instr) {
        bytecode.add(instr);
        lines.add(currentLine);
    }

    public int addConst(Object val) {
        if (val == null) { int i = constPool.size(); constPool.add(null); return i; }
        return constMap.computeIfAbsent(val, k -> { int i = constPool.size(); constPool.add(k); return i; });
    }
    public void emit(OpCode op, Object cv) { add((op.code() << 16) | (addConst(cv) & 0xFFFF)); }
    public void emitRaw(OpCode op, int raw) { add((op.code() << 16) | (raw & 0xFFFF)); }
    public void emit(OpCode op)             { add((op.code() << 16) | NO_ARG); }
    public int size()                       { return bytecode.size(); }
    public void patch(int idx, int arg)     { int old = bytecode.get(idx); bytecode.set(idx, (old & 0xFFFF0000) | (arg & 0xFFFF)); }

    public CompiledCode build() {
        return new CompiledCode(
                bytecode.stream().mapToInt(Integer::intValue).toArray(),
                constPool.toArray(),
                lines.stream().mapToInt(Integer::intValue).toArray());
    }
}
