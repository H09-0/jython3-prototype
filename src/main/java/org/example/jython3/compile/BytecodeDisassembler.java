package org.example.jython3.compile;

import org.example.jython3.runtime.PyFunction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Human-readable bytecode disassembler for {@link CompiledCode} units and nested
 * functions/classes, useful for debugging compiler output and jump targets.
 */
public final class BytecodeDisassembler {
    private static final OpCode[] OP_VALUES = OpCode.byCode();
    private static final int NO_ARG = 0xFFFF;

    private BytecodeDisassembler() {}

    public static String disassemble(CompiledCode code) {
        return disassemble(code, "<module>");
    }

    public static String disassemble(CompiledCode code, String unitName) {
        var sb = new StringBuilder();
        var seen = new HashSet<CompiledCode>();
        disassembleUnit(code, unitName, sb, seen);
        return sb.toString();
    }

    private static void disassembleUnit(CompiledCode code, String unitName,
                                        StringBuilder sb, Set<CompiledCode> seen) {
        if (code == null || !seen.add(code)) return;
        sb.append("=== ").append(unitName)
          .append(code.hasYield() ? " [generator]" : "")
          .append(" ===\n");
        int[] bc = code.bytecode();
        Object[] pool = code.constPool();
        var nested = new ArrayDeque<NamedCode>();

        for (int pc = 0; pc < bc.length; pc++) {
            int instr = bc[pc];
            int opCode = instr >>> 16;
            int ai = instr & 0xFFFF;
            OpCode op = (opCode >= 0 && opCode < OP_VALUES.length) ? OP_VALUES[opCode] : null;
            int line = code.lineAt(pc);
            sb.append(String.format("%4d  L%-4d %-22s", pc, line, op != null ? op.name() : ("UNKNOWN_" + opCode)));
            if (op != null) {
                sb.append(formatOperand(op, ai, pool, pc));
            } else {
                sb.append(ai);
            }
            sb.append('\n');
        }

        for (Object c : pool) {
            if (c instanceof PyFunction fn && fn.code() != null) {
                nested.add(new NamedCode(fn.name(), fn.code()));
            } else if (c instanceof CompiledCode cc) {
                nested.add(new NamedCode("<code@" + System.identityHashCode(cc) + ">", cc));
            }
        }
        while (!nested.isEmpty()) {
            var nc = nested.removeFirst();
            sb.append('\n');
            disassembleUnit(nc.code(), nc.name(), sb, seen);
        }
    }

    private static String formatOperand(OpCode op, int ai, Object[] pool, int pc) {
        return switch (op) {
            case LOAD_CONST -> ai == NO_ARG ? "None" : formatConst(ai, pool);
            case LOAD_FAST, LOAD_GLOBAL, STORE_FAST, LOAD_GLOBAL_NAME, STORE_GLOBAL_NAME,
                 DELETE_FAST, DELETE_NAME_SILENT, LOAD_ATTR, STORE_ATTR, DELETE_ATTR,
                 IMPORT_MODULE, MAKE_FUNCTION, BUILD_CLASS, CALL_FUNCTION_KW -> formatConst(ai, pool);
            case JUMP, JUMP_IF_FALSE, JUMP_IF_TRUE_OR_POP, JUMP_IF_FALSE_OR_POP,
                 FOR_ITER, PUSH_EXCEPT_HANDLER -> "-> " + ai;
            case YIELD_FROM -> "(delegate, done -> " + ai + ")";
            case YIELD_VALUE -> "(suspend, resume -> " + (pc + 1) + ")";
            case CALL_FUNCTION, CALL_FUNCTION_EX, BUILD_LIST, BUILD_DICT, BUILD_TUPLE,
                 BUILD_SET, BUILD_SLICE, UNPACK_SEQUENCE, MAKE_FUNCTION_DEFAULTS,
                 ASSERT, RERAISE -> String.valueOf(ai);
            default -> "";
        };
    }

    private static String formatConst(int ai, Object[] pool) {
        if (pool == null || ai < 0 || ai >= pool.length) {
            return "<out-of-bounds:" + ai + ">";
        }
        Object v = pool[ai];
        if (v instanceof String s) return "\"" + s + "\"";
        if (v instanceof PyFunction fn) return "<fn " + fn.name() + ">";
        if (v instanceof CompiledCode) return "<code:" + ai + ">";
        return String.valueOf(v);
    }

    private record NamedCode(String name, CompiledCode code) {}
}
