package org.example.jython3.compile;

import org.example.jython3.runtime.PyFunction;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Static bytecode verifier for {@link CompiledCode}.
 *
 * <p>Checks opcode validity, constant-pool bounds, jump/handler target bounds,
 * and abstract operand-stack depth consistency across all reachable control-flow
 * paths (including nested functions and classes).
 */
public final class BytecodeValidator {
    private static final OpCode[] OP_VALUES = OpCode.byCode();
    private static final int NO_ARG = 0xFFFF;

    private BytecodeValidator() {}

    public static void validate(CompiledCode code) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<CompiledCode, Boolean>());
        validateUnit(code, "<module>", false, seen);
    }

    private static void validateUnit(CompiledCode code, String name, boolean allowYield, Set<CompiledCode> seen) {
        if (code == null) throw new IllegalStateException("Null CompiledCode for " + name);
        if (!seen.add(code)) return;

        int[] bc = code.bytecode();
        Object[] pool = code.constPool();
        int[] lines = code.lines();
        if (bc == null || bc.length == 0) {
            throw new IllegalStateException("Empty bytecode for " + name);
        }
        if (lines == null || lines.length != bc.length) {
            throw new IllegalStateException("Line table length mismatch for " + name);
        }

        // Pass 1: linear structural validation (opcodes, pool bounds, jump targets)
        for (int pc = 0; pc < bc.length; pc++) {
            int instr = bc[pc];
            int opCode = instr >>> 16;
            int ai = instr & 0xFFFF;
            OpCode op = (opCode >= 0 && opCode < OP_VALUES.length) ? OP_VALUES[opCode] : null;
            if (op == null) {
                throw new IllegalStateException(name + "@" + pc + ": invalid opcode " + opCode);
            }
            if (op == OpCode.YIELD_VALUE && !allowYield) {
                throw new IllegalStateException(name + "@" + pc + ": YIELD_VALUE outside generator function");
            }
            if (op == OpCode.YIELD_FROM && !allowYield) {
                throw new IllegalStateException(name + "@" + pc + ": YIELD_FROM outside generator function");
            }
            if (isJumpOp(op) && (ai < 0 || ai >= bc.length)) {
                throw new IllegalStateException(name + "@" + pc + " (" + op + "): jump target " + ai + " out of bounds [0, " + bc.length + ")");
            }
            if (usesPool(op, ai) && (pool == null || ai < 0 || ai >= pool.length)) {
                throw new IllegalStateException(name + "@" + pc + " (" + op + "): constPool index " + ai + " out of bounds");
            }
        }

        // Pass 2: abstract operand stack depth across reachable paths
        int[] knownDepth = new int[bc.length];
        Arrays.fill(knownDepth, -1);
        var worklist = new ArrayDeque<Integer>();
        knownDepth[0] = 0;
        worklist.add(0);

        while (!worklist.isEmpty()) {
            int pc = worklist.removeFirst();
            int depth = knownDepth[pc];
            int instr = bc[pc];
            OpCode op = OP_VALUES[instr >>> 16];
            int ai = instr & 0xFFFF;

            if (op == OpCode.HALT || op == OpCode.RETURN_VALUE || op == OpCode.RAISE
                    || op == OpCode.RAISE_ACTIVE || op == OpCode.RERAISE || op == OpCode.RAISE_FROM
                    || op == OpCode.ASSERT) {
                int minNeed = (op == OpCode.RETURN_VALUE || op == OpCode.RAISE) ? 1
                        : (op == OpCode.RAISE_FROM ? 2
                        : (op == OpCode.RERAISE && ai == 0 ? 1
                        : (op == OpCode.ASSERT && ai == 1 ? 1 : 0)));
                if (depth < minNeed) {
                    throw new IllegalStateException(name + "@" + pc + " (" + op + "): stack underflow (depth=" + depth + ", need=" + minNeed + ")");
                }
                continue;
            }

            if (op == OpCode.JUMP) {
                enqueue(name, ai, depth, knownDepth, worklist);
                continue;
            }
            if (op == OpCode.JUMP_IF_FALSE) {
                if (depth < 1) throw new IllegalStateException(name + "@" + pc + ": stack underflow on JUMP_IF_FALSE");
                enqueue(name, ai, depth - 1, knownDepth, worklist);
                enqueueFallthrough(name, pc, bc.length, depth - 1, knownDepth, worklist);
                continue;
            }
            if (op == OpCode.JUMP_IF_TRUE_OR_POP || op == OpCode.JUMP_IF_FALSE_OR_POP) {
                if (depth < 1) throw new IllegalStateException(name + "@" + pc + ": stack underflow on " + op);
                enqueue(name, ai, depth, knownDepth, worklist);       // taken: keeps condition on TOS
                enqueueFallthrough(name, pc, bc.length, depth - 1, knownDepth, worklist); // fallthrough: pops
                continue;
            }
            if (op == OpCode.FOR_ITER) {
                if (depth < 1) throw new IllegalStateException(name + "@" + pc + ": missing iterator on FOR_ITER");
                enqueue(name, ai, depth - 1, knownDepth, worklist);   // exhausted: pops iterator
                enqueueFallthrough(name, pc, bc.length, depth + 1, knownDepth, worklist); // next: pushes item
                continue;
            }
            if (op == OpCode.YIELD_FROM) {
                if (depth < 2) throw new IllegalStateException(name + "@" + pc + ": stack underflow on YIELD_FROM");
                enqueue(name, ai, depth - 1, knownDepth, worklist);   // done: pops sub_iter+sent, pushes return value
                continue;
            }
            if (op == OpCode.PUSH_EXCEPT_HANDLER) {
                enqueue(name, ai, depth + 1, knownDepth, worklist);   // handler receives exception on stack
                enqueueFallthrough(name, pc, bc.length, depth, knownDepth, worklist);
                continue;
            }
            if (op == OpCode.WITH_EXCEPT_EXIT) {
                if (depth < 2) throw new IllegalStateException(name + "@" + pc + ": stack underflow on WITH_EXCEPT_EXIT");
                // Followed by JUMP_IF_TRUE_OR_POP -> POP_TOP (when True: stack has [True] -> depth-1)
                // and fallthrough to RERAISE (when False: stack has [exc, False] -> depth)
                if (pc + 1 < bc.length && OP_VALUES[bc[pc + 1] >>> 16] == OpCode.JUMP_IF_TRUE_OR_POP) {
                    int suppressTarget = bc[pc + 1] & 0xFFFF;
                    enqueue(name, suppressTarget, depth - 1, knownDepth, worklist);
                    enqueueFallthrough(name, pc + 1, bc.length, depth - 1, knownDepth, worklist);
                    continue;
                }
            }

            int[] needAndDelta = stackEffect(op, ai, pool);
            if (depth < needAndDelta[0]) {
                throw new IllegalStateException(name + "@" + pc + " (" + op + "): stack underflow (depth=" + depth + ", need=" + needAndDelta[0] + ")");
            }
            int nextDepth = depth + needAndDelta[1];
            if (nextDepth < 0) {
                throw new IllegalStateException(name + "@" + pc + " (" + op + "): negative stack depth " + nextDepth);
            }
            enqueueFallthrough(name, pc, bc.length, nextDepth, knownDepth, worklist);
        }

        if (pool != null) {
            for (Object c : pool) {
                if (c instanceof PyFunction fn && fn.code() != null) {
                    validateUnit(fn.code(), fn.name(), fn.code().hasYield(), seen);
                } else if (c instanceof CompiledCode cc) {
                    validateUnit(cc, "<nested>", false, seen);
                }
            }
        }
    }

    private static void enqueueFallthrough(String name, int pc, int len, int depth,
                                           int[] knownDepth, ArrayDeque<Integer> worklist) {
        if (pc + 1 >= len) {
            throw new IllegalStateException(name + "@" + pc + ": control falls off end of bytecode");
        }
        enqueue(name, pc + 1, depth, knownDepth, worklist);
    }

    private static void enqueue(String name, int target, int depth,
                                int[] knownDepth, ArrayDeque<Integer> worklist) {
        if (knownDepth[target] == -1) {
            knownDepth[target] = depth;
            worklist.add(target);
        } else if (knownDepth[target] != depth) {
            throw new IllegalStateException(name + "@" + target + ": inconsistent stack depth ("
                    + knownDepth[target] + " vs " + depth + ")");
        }
    }

    private static boolean isJumpOp(OpCode op) {
        return switch (op) {
            case JUMP, JUMP_IF_FALSE, JUMP_IF_TRUE_OR_POP, JUMP_IF_FALSE_OR_POP,
                 FOR_ITER, PUSH_EXCEPT_HANDLER, YIELD_FROM -> true;
            default -> false;
        };
    }

    private static boolean usesPool(OpCode op, int ai) {
        return switch (op) {
            case LOAD_CONST -> ai != NO_ARG;
            case LOAD_FAST, LOAD_GLOBAL, STORE_FAST, LOAD_GLOBAL_NAME, STORE_GLOBAL_NAME,
                 DELETE_FAST, DELETE_NAME_SILENT, LOAD_ATTR, STORE_ATTR, DELETE_ATTR,
                 IMPORT_MODULE, MAKE_FUNCTION, BUILD_CLASS, CALL_FUNCTION_KW -> true;
            default -> false;
        };
    }

    /** Returns {minStackNeeded, netDelta} for non-branching opcodes. */
    private static int[] stackEffect(OpCode op, int ai, Object[] pool) {
        return switch (op) {
            case LOAD_CONST -> {
                if (ai != NO_ARG && pool != null && pool[ai] instanceof String s
                        && (s.startsWith("@global:") || s.startsWith("@nonlocal:"))) {
                    yield new int[]{0, 0};
                }
                yield new int[]{0, 1};
            }
            case LOAD_FAST, LOAD_GLOBAL, LOAD_GLOBAL_NAME -> new int[]{0, 1};
            case STORE_FAST, STORE_GLOBAL_NAME, POP_TOP, EXECFILE -> new int[]{1, -1};
            case STORE_SUBSCR -> new int[]{3, -3};
            case STORE_ATTR -> new int[]{2, -2};
            case DELETE_SUBSCR -> new int[]{2, -2};
            case DELETE_ATTR -> new int[]{1, -1};
            case DELETE_FAST, DELETE_NAME_SILENT, POP_EXCEPT_HANDLER, BEGIN_EXCEPT_HANDLER,
                 END_EXCEPT_HANDLER -> new int[]{0, 0};
            case UNARY_MINUS, LOGICAL_NOT, BITWISE_INVERT, GET_ITER, LOAD_ATTR, YIELD_VALUE -> new int[]{1, 0};
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, FLOOR_DIVIDE, MODULO, POWER,
                 INPLACE_ADD, INPLACE_SUBTRACT, INPLACE_MULTIPLY,
                 INPLACE_AND, INPLACE_OR, INPLACE_XOR,
                 COMPARE_EQ, COMPARE_NEQ, COMPARE_LT, COMPARE_GT, COMPARE_LTE, COMPARE_GTE,
                 COMPARE_IN, COMPARE_NOT_IN, COMPARE_IS, COMPARE_IS_NOT,
                 BINARY_SUBSCR, BITWISE_AND, BITWISE_OR, BITWISE_XOR, LSHIFT, RSHIFT -> new int[]{2, -1};
            case DUP_TOP -> new int[]{1, 1};
            case DUP_TOP_TWO -> new int[]{2, 2};
            case ROT_TWO -> new int[]{2, 0};
            case ROT_THREE -> new int[]{3, 0};
            case BUILD_LIST, BUILD_TUPLE, BUILD_SET -> new int[]{ai, 1 - ai};
            case BUILD_DICT -> new int[]{2 * ai, 1 - 2 * ai};
            case BUILD_SLICE -> new int[]{ai, 1 - ai};
            case LIST_COMP_INIT, SET_COMP_INIT -> new int[]{0, 1};
            case LIST_COMP_APPEND, SET_COMP_ADD, LIST_EXTEND, DICT_MERGE -> new int[]{2, -1};
            case UNPACK_SEQUENCE -> new int[]{1, ai - 1};
            case ASSERT -> new int[]{ai == 1 ? 1 : 0, ai == 1 ? -1 : 0};
            case IMPORT_MODULE -> new int[]{0, 0};
            case MAKE_FUNCTION -> new int[]{1, -1};
            case MAKE_FUNCTION_DEFAULTS -> {
                int nDefaults = ai >>> 2;
                yield new int[]{1 + nDefaults, -nDefaults};
            }
            case BUILD_CLASS -> new int[]{2, -2};
            case CALL_FUNCTION -> new int[]{1 + ai, -ai};
            case CALL_FUNCTION_KW -> {
                KwCallInfo info = (KwCallInfo) pool[ai];
                int nArgs = info.nPos() + info.kwNames().length;
                yield new int[]{1 + nArgs, -nArgs};
            }
            case CALL_FUNCTION_EX -> {
                int n = ai == 1 ? 3 : 2;
                yield new int[]{n, 1 - n};
            }
            case COMPARE_EXC_TYPE -> new int[]{2, 0}; // pops type, peeks exc, pushes bool
            case SETUP_WITH -> new int[]{1, 1};        // pops cm, pushes exit_fn + entered
            case END_WITH -> new int[]{1, -1};         // pops exit_fn
            case WITH_EXCEPT_EXIT -> new int[]{2, 0};  // pops exc + exit_fn, pushes (bool | exc+bool); jump balances
            default -> new int[]{0, 0};
        };
    }
}
