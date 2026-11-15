package org.example.jython3;

import org.example.jython3.compile.BytecodeValidator;
import org.example.jython3.compile.CompiledCode;
import org.example.jython3.compile.OpCode;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for BytecodeDisassembler, BytecodeValidator (jump targets, pool bounds,
 * stack-depth balance across control-flow exits), and with-suppression stack cleanup.
 */
class BytecodeInspectionTest {

    @Test
    @DisplayName("BytecodeValidator accepts complex control flow, closures, comprehensions, and with/try/finally")
    void validatorVerifiesAllConstructs() {
        var interp = Jython3Interpreter.trusted();
        String complex = """
            class CM:
                def __enter__(self):
                    return 1
                def __exit__(self, t, v, tb):
                    return True

            def fn(items, *args, **kwargs):
                total = 0
                for x in items:
                    with CM():
                        if x == 0:
                            continue
                        if x > 10:
                            break
                        try:
                            total += (x & 3) | (1 << 2)
                            if 0 < total < 100:
                                pass
                        except ValueError as e:
                            return total
                        else:
                            total += 1
                        finally:
                            total += 0
                squares = [i * i for i in range(4) if i % 2 == 0]
                d = {i: i for i in range(2)}
                s = {i for i in range(3)}
                return total, squares, d, s, lambda k: k + total
            """;
        CompiledCode code = interp.compileSource(complex);
        BytecodeValidator.validate(code);

        String dis = interp.disassemble(complex);
        assertTrue(dis.contains("=== <module> ==="), dis);
        assertTrue(dis.contains("=== fn ==="), dis);
        assertTrue(dis.contains("PUSH_EXCEPT_HANDLER"), dis);
        assertTrue(dis.contains("SETUP_WITH"), dis);
    }

    @Test
    @DisplayName("BytecodeValidator rejects corrupted jumps, out-of-bounds pool indices, and stack underflow")
    void validatorRejectsMalformedBytecode() {
        int badJumpInstr = (OpCode.JUMP.code() << 16) | 99;
        var badJump = new CompiledCode(new int[]{badJumpInstr, OpCode.HALT.code() << 16}, new Object[0], new int[]{1, 1});
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(badJump));

        int badPoolInstr = (OpCode.LOAD_CONST.code() << 16) | 5;
        var badPool = new CompiledCode(new int[]{badPoolInstr, OpCode.HALT.code() << 16}, new Object[0], new int[]{1, 1});
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(badPool));

        int underflowInstr = OpCode.POP_TOP.code() << 16;
        var underflow = new CompiledCode(new int[]{underflowInstr, OpCode.HALT.code() << 16}, new Object[0], new int[]{1, 1});
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(underflow));
    }

    @Test
    @DisplayName("with __exit__ returning True inside a for loop leaves stack balanced (regression)")
    void suppressedWithInsideForLoopDoesNotLeakStack() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class Suppress:
                def __enter__(self):
                    return self
                def __exit__(self, t, v, tb):
                    return True

            seen = []
            for i in [1, 2, 3]:
                with Suppress():
                    seen.append(i)
                    if i == 2:
                        raise ValueError("suppressed")
                    seen.append(i * 10)
            ok = seen == [1, 10, 2, 3, 30]
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("seen")));
    }
}
