package org.example.jython3.compile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CodeBuilder {
    static final int NO_ARG = 0xFFFF;
    private final List<Integer> bytecode = new ArrayList<>();
    private final List<Object> constPool = new ArrayList<>();
    private final Map<Object, Integer> constMap = new HashMap<>();

    public int addConst(Object val) {
        if (val == null) { int i = constPool.size(); constPool.add(null); return i; }
        return constMap.computeIfAbsent(val, k -> { int i = constPool.size(); constPool.add(k); return i; });
    }
    public void emit(OpCode op, Object cv) { bytecode.add((op.code() << 16) | (addConst(cv) & 0xFFFF)); }
    public void emitRaw(OpCode op, int raw) { bytecode.add((op.code() << 16) | (raw & 0xFFFF)); }
    public void emit(OpCode op)             { bytecode.add((op.code() << 16) | NO_ARG); }
    public int size()                       { return bytecode.size(); }
    public void patch(int idx, int arg)     { int old = bytecode.get(idx); bytecode.set(idx, (old & 0xFFFF0000) | (arg & 0xFFFF)); }
    public CompiledCode build()             { return new CompiledCode(bytecode.stream().mapToInt(Integer::intValue).toArray(), constPool.toArray()); }
}
