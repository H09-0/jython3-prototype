package org.example.jython3.compile;

/**
 * Bytecode operations. {@link #code()} is the value stored in the high 16 bits
 * of an instruction. Numbers are stable: do not renumber an existing operation.
 */
public enum OpCode {
    LOAD_CONST(0), LOAD_FAST(1), LOAD_GLOBAL(2), STORE_FAST(3), STORE_SUBSCR(4), STORE_ATTR(5),
    ADD(6), SUBTRACT(7), MULTIPLY(8), DIVIDE(9), FLOOR_DIVIDE(10), MODULO(11), POWER(12), UNARY_MINUS(13),
    COMPARE_EQ(14), COMPARE_NEQ(15), COMPARE_LT(16), COMPARE_GT(17), COMPARE_LTE(18), COMPARE_GTE(19),
    COMPARE_IN(20), COMPARE_NOT_IN(21), COMPARE_IS(22), COMPARE_IS_NOT(23),
    LOGICAL_NOT(24),
    PRINT(25), PRINT_MULTI(26),
    JUMP_IF_FALSE(27), JUMP_IF_TRUE_OR_POP(28), JUMP_IF_FALSE_OR_POP(29), JUMP(30),
    IMPORT_MODULE(31), MAKE_FUNCTION(32), BUILD_CLASS(33), CALL_FUNCTION(34), RETURN_VALUE(35), YIELD_VALUE(36),
    POP_TOP(37), DUP_TOP(38),
    BUILD_LIST(39), BUILD_DICT(40), BINARY_SUBSCR(41), GET_ITER(42), FOR_ITER(43),
    DELETE_FAST(44), DELETE_SUBSCR(45),
    LOAD_ATTR(46),
    PUSH_EXCEPT_HANDLER(47), POP_EXCEPT_HANDLER(48), RAISE(49),
    HALT(50),
    EXECFILE(51),          // arg: path string on stack
    COMPARE_EXC_TYPE(52),  // pops a class, keeps the exception value, pushes whether it matches
    RERAISE(53),           // pops an exception value and raises it again
    RAISE_ACTIVE(54),      // bare raise: re-raises the active exception
    BEGIN_EXCEPT_HANDLER(55), // marks the caught value as the active exception
    END_EXCEPT_HANDLER(56),   // restores the previously active exception
    DELETE_NAME_SILENT(57),   // deletes name if present, does not throw NameError if absent
    BUILD_SLICE(58),          // raw arg: 2 (start, stop) or 3 (start, stop, step)
    BUILD_TUPLE(59),          // raw arg: element count on stack
    // ── arg: (nPos << 16) | nKw; stack: callable, pos0..posN-1, kw0..kwN-1, kwNames-tuple
    CALL_FUNCTION_KW(60),
    // ── arg: (nDefaults << 2) | flags; flags: bit0=hasArgs, bit1=hasKwargs
    // stack before MAKE_FUNCTION: PyFunction prototype, then nDefaults default values
    MAKE_FUNCTION_DEFAULTS(61),
    // ── Comprehensions (compiled as inline loop + accumulator)
    LIST_COMP_INIT(62),     // push fresh list onto stack for comprehension accumulation
    LIST_COMP_APPEND(63),   // pop value, append to list at stack[top-1]
    SET_COMP_INIT(64),      // push fresh set
    SET_COMP_ADD(65),       // pop value, add to set at stack[top-1]
    BUILD_SET(66),          // raw arg: element count — build a set from N stack items
    // ── global / nonlocal
    LOAD_GLOBAL_NAME(67),   // load from globals scope; NameError if absent
    STORE_GLOBAL_NAME(68),  // store into globals scope
    // ── with statement
    SETUP_WITH(69),         // arg: pool index of __exit__ attribute name ("__exit__")
                            // stack: context_manager → pushes (exit_fn, value_from___enter__)
    END_WITH(70),           // calls exit_fn(None, None, None); cleans stack entry
    WITH_EXCEPT_EXIT(71),   // calls exit_fn(type, value, tb); pushes bool result
    // ── sequence unpacking
    UNPACK_SEQUENCE(72),    // raw arg: count — pops one iterable, pushes count items
    // ── assert
    ASSERT(73);             // raw arg: 0=no message, 1=has message; pops cond [+ msg]

    private final int code;

    OpCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    private static final OpCode[] BY_CODE = buildTable();

    private static OpCode[] buildTable() {
        int max = 0;
        for (OpCode op : values()) {
            max = Math.max(max, op.code);
        }
        OpCode[] table = new OpCode[max + 1];
        for (OpCode op : values()) {
            if (table[op.code] != null) {
                throw new ExceptionInInitializerError("duplicate opcode code " + op.code);
            }
            table[op.code] = op;
        }
        return table;
    }

    /** Lookup table indexed by {@link #code()}. Empty slots are null. */
    public static OpCode[] byCode() {
        return BY_CODE;
    }
}
