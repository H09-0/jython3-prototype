package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security hardening tests covering memory amplification, CPU DoS, infinite
 * dunder recursion, and sandbox isolation across the VM and stdlib.
 */
class InterpreterSecurityHardeningTest {

    @Test
    @DisplayName("Memory amplification DoS vectors (range, %f width, format width, str.replace, str.join, +) are blocked before OOM")
    void memoryAmplificationDosBlocked() {
        var tightLimits = new ExecutionLimits(100_000, Duration.ofSeconds(2), 50_000, 1_000, 1_000, 500);
        try (var rt = EmbeddedRuntime.builder()
                .limits(tightLimits)
                .uiToolkit(new HeadlessUiToolkit())
                .build()) {

            // 1. Huge range() blocked before allocation
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("range(0, 2000000000)")).typeName());

            // 2. %999999999f blocked before String.format OOM
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("'%999999999f' % 1.0")).typeName());

            // 3. format() huge width blocked
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("format('x', '999999999')")).typeName());

            // 4. str.replace amplification blocked
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("('a' * 200).replace('a', 'b' * 20)")).typeName());

            // 5. str.join amplification blocked
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("('x' * 50).join(['a'] * 50)")).typeName());

            // 6. str + str exceeding maxStringLength blocked
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("s = 'a' * 600\ns + s")).typeName());

            // 7. list + list exceeding maxCollectionSize blocked
            assertEquals("OverflowError",
                    assertThrows(InterpreterException.class, () -> rt.eval("xs = [1] * 600\nxs + xs")).typeName());
        }
    }

    @Test
    @DisplayName("CPU DoS via giant integer string parsing and infinite __str__/__repr__ recursion are bounded")
    void cpuDosAndDunderRecursionBlocked() {
        try (var rt = EmbeddedRuntime.builder()
                .limits(ExecutionLimits.SANDBOXED)
                .uiToolkit(new HeadlessUiToolkit())
                .build()) {

            // Huge int string conversion (> 10,000 digits) rejected immediately
            InterpreterException digitErr = assertThrows(InterpreterException.class,
                    () -> rt.eval("int('9' * 20000)"));
            assertEquals("ValueError", digitErr.typeName());

            // Infinite __str__ recursion triggers RecursionError cleanly
            InterpreterException recErr = assertThrows(InterpreterException.class, () -> rt.eval("""
                class LoopStr:
                    def __str__(self):
                        return str(self)
                str(LoopStr())
                """));
            assertEquals("RecursionError", recErr.typeName());

            // Interpreter remains healthy afterwards
            rt.eval("ok = 40 + 2");
            assertTrue("42".equals(rt.getGlobal("ok").toString()));
        }
    }

    @Test
    @DisplayName("Sandbox blocks execfile, Java reflection, and import-from-disk escapes")
    void sandboxBlocksExecfileAndReflection(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Files.writeString(tmp.resolve("disk_secret.py"), "X = 999\n");
        try (var rt = EmbeddedRuntime.builder().limits(ExecutionLimits.SANDBOXED).build()) {
            for (String probe : new String[]{
                    "execfile('/etc/passwd')",
                    "import io\nio.open('/etc/passwd', 'r')",
                    "import java.lang.Runtime"
            }) {
                InterpreterException ex = assertThrows(InterpreterException.class, () -> rt.eval(probe));
                assertEquals("SecurityError", ex.typeName(), "Expected SecurityError for: " + probe);
            }
            InterpreterException importErr = assertThrows(InterpreterException.class, () -> rt.eval(
                    "import sys\nsys.path.append(" + reprPath(tmp) + ")\nimport disk_secret\n"));
            assertTrue("SecurityError".equals(importErr.typeName()) || "ImportError".equals(importErr.typeName()),
                    "Expected SecurityError or ImportError for disk import, got: " + importErr.typeName());
        }
    }

    private static String reprPath(java.nio.file.Path p) {
        return "\"" + p.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
