package org.example.jython3.security;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.compile.BytecodeValidator;
import org.example.jython3.compile.CodeBuilder;
import org.example.jython3.compile.CompiledCode;
import org.example.jython3.compile.OpCode;
import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Security verification for parser, compiler, and bytecode verification boundaries.
 * Standards: OWASP Top 10:2025 A06/A10, MITRE CWE-20 (Improper Input Validation),
 * CWE-400 (Uncontrolled Resource Consumption), CWE-674 (Uncontrolled Recursion).
 */
@Tag("security")
class GrammarAndParserSecurityTest {

    @Test
    @DisplayName("CWE-400: Source length exceeding ExecutionLimits.maxSourceLength is rejected before parsing")
    void sourceLengthLimitEnforcedBeforeParse() {
        var tiny = new ExecutionLimits(10_000, Duration.ofSeconds(1), 64, 100, 100, 50);
        try (var rt = EmbeddedRuntime.builder().limits(tiny).build()) {
            String bigSource = "x = 1\n".repeat(30);
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class, () -> rt.eval(bigSource)).typeName());
        }
    }

    @Test
    @DisplayName("CWE-20: Malformed syntax, bad indentation, and unterminated strings fail safely with SyntaxError")
    void malformedSyntaxFailsCleanly() {
        try (var rt = EmbeddedRuntime.builder().build()) {
            for (String bad : new String[]{
                    "if True:\nx = 1",
                    "def f(:\n    pass",
                    "s = 'unterminated",
                    "x = [1, 2",
                    "a = 1\n    b = 2",
                    "break",
                    "continue",
                    "return 1",
                    "yield 1"
            }) {
                InterpreterException ex = assertThrows(InterpreterException.class, () -> rt.eval(bad), bad);
                assertEquals("SyntaxError", ex.typeName(), bad);
            }
        }
    }

    @Test
    @DisplayName("CWE-20 / CWE-674: BytecodeValidator rejects invalid opcodes, out-of-bounds jumps, and stack underflows")
    void bytecodeValidatorRejectsCorruptBytecode() {
        // Out-of-bounds jump
        var badJump = new CodeBuilder();
        badJump.emitRaw(OpCode.JUMP, 42);
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(badJump.build()));

        // Stack underflow on RETURN_VALUE
        var underflow = new CodeBuilder();
        underflow.emit(OpCode.RETURN_VALUE);
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(underflow.build()));

        // YIELD_VALUE outside generator
        var strayYield = new CodeBuilder();
        strayYield.emit(OpCode.LOAD_CONST, 1);
        strayYield.emit(OpCode.YIELD_VALUE);
        strayYield.emit(OpCode.RETURN_VALUE);
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(strayYield.build()));

        // Invalid raw opcode number
        CompiledCode bogusOp = new CompiledCode(new int[]{(250 << 16)}, new Object[0], new int[]{1});
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(bogusOp));
    }
}
