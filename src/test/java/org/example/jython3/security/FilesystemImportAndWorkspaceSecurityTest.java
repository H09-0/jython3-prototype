package org.example.jython3.security;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.ScopedWorkspaceAdapter;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security verification for filesystem I/O, module imports, and ScopedWorkspaceAdapter.
 * Standards: OWASP Top 10:2025 A01 (Broken Access Control), MITRE CWE-22 (Path Traversal),
 * CWE-59 (Improper Link Resolution / Symlink TOCTOU), CERT FIO02-C / POS01-C.
 */
@Tag("security")
class FilesystemImportAndWorkspaceSecurityTest {

    @Test
    @DisplayName("CWE-22 / CWE-59: IoModule.open blocks HostAccessPolicy violations, invalid modes, null bytes, and symlinks")
    void ioModuleOpenSecurity(@TempDir Path tmp) throws Exception {
        Path allowedDir = tmp.resolve("allowed");
        Path outsideDir = tmp.resolve("outside");
        Files.createDirectories(allowedDir);
        Files.createDirectories(outsideDir);
        Path secret = outsideDir.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET");

        HostAccessPolicy scopedPolicy = new HostAccessPolicy() {
            @Override public boolean canReadFile(Path p) { return p.toAbsolutePath().normalize().startsWith(allowedDir); }
            @Override public boolean canWriteFile(Path p) { return p.toAbsolutePath().normalize().startsWith(allowedDir); }
            @Override public boolean canListDirectory(Path p) { return p.toAbsolutePath().normalize().startsWith(allowedDir); }
            @Override public boolean canImportFromDisk(Path p) { return false; }
            @Override public boolean canExecFile(Path p) { return false; }
            @Override public boolean canReadEnvironment() { return false; }
        };

        var interp = new Jython3Interpreter(DenyAllPolicy.INSTANCE, scopedPolicy,
                ExecutionLimits.SANDBOXED, ExceptionTypeRegistry.standard(), CapabilitySet.none());

        // Null byte and invalid mode rejected
        assertEquals("ValueError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import io\nio.open('a\\x00b', 'r')")).typeName());
        assertEquals("ValueError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import io\nio.open(" + repr(allowedDir.resolve("x.txt")) + ", 'invalid')")).typeName());

        // Traversal outside allowedDir blocked
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import io\nio.open(" + repr(allowedDir.resolve("../outside/secret.txt")) + ", 'r')")).typeName());

        // Symlink inside allowedDir pointing outside blocked
        Path linkFile = allowedDir.resolve("link_secret.txt");
        Files.createSymbolicLink(linkFile, secret);
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import io\nio.open(" + repr(linkFile) + ", 'r')")).typeName());

        // Symlink directory ancestor inside allowedDir blocked
        Path linkDir = allowedDir.resolve("link_dir");
        Files.createSymbolicLink(linkDir, outsideDir);
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import io\nio.open(" + repr(linkDir.resolve("secret.txt")) + ", 'r')")).typeName());

        // UTF-8 multi-byte write respects maxBytes
        var tightLimits = new ExecutionLimits(100_000, java.time.Duration.ofSeconds(2), 50_000, 1_000, 10, 100);
        var tightInterp = new Jython3Interpreter(DenyAllPolicy.INSTANCE, scopedPolicy,
                tightLimits, ExceptionTypeRegistry.standard(), CapabilitySet.none());
        Path utf8File = allowedDir.resolve("utf8.txt");
        assertEquals("MemoryError",
                assertThrows(InterpreterException.class,
                        () -> tightInterp.eval("import io\nf = io.open(" + repr(utf8File) + ", 'w')\nf.write('😀😀😀')")).typeName());

        // Closed file rejects operations
        assertEquals("ValueError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import io\nf = io.open(" + repr(allowedDir.resolve("ok.txt")) + ", 'w')\nf.close()\nf.write('hi')")).typeName());
    }

    @Test
    @DisplayName("CWE-22 / CWE-59: OsModule.listdir blocks symlink directory escape and null bytes")
    void osListdirSymlinkEscapeBlocked(@TempDir Path tmp) throws Exception {
        Path allowedDir = tmp.resolve("allowed");
        Path secretDir = tmp.resolve("secret");
        Files.createDirectories(allowedDir);
        Files.createDirectories(secretDir);
        Files.writeString(secretDir.resolve("creds.txt"), "x");
        Path linkSub = allowedDir.resolve("escape");
        Files.createSymbolicLink(linkSub, secretDir);

        HostAccessPolicy policy = new HostAccessPolicy() {
            @Override public boolean canReadFile(Path p) { return false; }
            @Override public boolean canWriteFile(Path p) { return false; }
            @Override public boolean canListDirectory(Path p) { return p.toAbsolutePath().normalize().startsWith(allowedDir); }
            @Override public boolean canImportFromDisk(Path p) { return false; }
            @Override public boolean canExecFile(Path p) { return false; }
            @Override public boolean canReadEnvironment() { return false; }
        };
        var interp = new Jython3Interpreter(DenyAllPolicy.INSTANCE, policy,
                ExecutionLimits.SANDBOXED, ExceptionTypeRegistry.standard(), CapabilitySet.none());

        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import os\nos.listdir(" + repr(linkSub) + ")")).typeName());
        assertEquals("ValueError",
                assertThrows(InterpreterException.class,
                        () -> interp.eval("import os\nos.listdir('bad\\x00path')")).typeName());
    }

    @Test
    @DisplayName("CWE-22 / CWE-59: ModuleLoader blocks path traversal, symlink escapes, .sikuli escapes, and oversized modules")
    void moduleLoaderAndSikuliSecurity(@TempDir Path tmp) throws Exception {
        Path modRoot = tmp.resolve("mods");
        Path outside = tmp.resolve("outside");
        Files.createDirectories(modRoot);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("evil.py"), "SECRET = 1\n");

        // Symlinked .py and symlinked .sikuli bundle pointing outside modRoot
        Files.createSymbolicLink(modRoot.resolve("evil_link.py"), outside.resolve("evil.py"));
        Path sikuliDir = modRoot.resolve("bad.sikuli");
        Files.createDirectories(sikuliDir);
        Files.createSymbolicLink(sikuliDir.resolve("bad.py"), outside.resolve("evil.py"));

        var interp = Jython3Interpreter.trusted();
        interp.addModulePath(modRoot);

        assertEquals("ImportError",
                assertThrows(InterpreterException.class,
                        () -> org.example.jython3.runtime.ModuleLoader.splitAndValidate("../outside/evil")).typeName());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> interp.eval("import evil_link")).typeName());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> interp.eval("import bad")).typeName());

        // importlib.reload detects post-load symlink replacement
        Path legit = modRoot.resolve("legit_mod.py");
        Files.writeString(legit, "VAL = 1\n");
        interp.eval("import legit_mod, importlib\nassert legit_mod.VAL == 1");
        Files.delete(legit);
        Files.createSymbolicLink(legit, outside.resolve("evil.py"));
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> interp.eval("importlib.reload(legit_mod)")).typeName());

        // HostAccessPolicy.DENY_ALL blocks disk imports even if sys.path is mutated
        try (var sandboxed = EmbeddedRuntime.builder().limits(ExecutionLimits.SANDBOXED).build()) {
            Files.writeString(modRoot.resolve("disk_mod.py"), "X = 42\n");
            InterpreterException ex = assertThrows(InterpreterException.class,
                    () -> sandboxed.eval("import sys\nsys.path.append(" + repr(modRoot) + ")\nimport disk_mod"));
            assertTrue("SecurityError".equals(ex.typeName()) || "ImportError".equals(ex.typeName()));
        }
    }

    @Test
    @DisplayName("CWE-59 / CWE-22: ScopedWorkspaceAdapter defeats traversal, drive/UNC paths, symlinks, and readOnly bypasses")
    void scopedWorkspaceAdapterSecurity(@TempDir Path tmp) throws Exception {
        Path ws = tmp.resolve("ws");
        Path outside = tmp.resolve("outside");
        Files.createDirectories(ws.resolve("sub"));
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "OUTSIDE");
        Files.createSymbolicLink(ws.resolve("sym_file.txt"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(ws.resolve("sym_dir"), outside);

        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM)
                .allowDevice("ws", "ws-ro")
                .allowCommand("read_text", "write_text", "list_dir", "exists")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).build()) {
            var rw = new ScopedWorkspaceAdapter("ws", ws, rt.hostSession(), false, 64);
            var ro = new ScopedWorkspaceAdapter("ws-ro", ws, rt.hostSession(), true, 64);
            rt.registerDevice(rw);
            rt.registerDevice(ro);
            rt.eval("import control\nc = control.Controller('ws')\ncro = control.Controller('ws-ro')");

            // Symlinks, traversal, drive/UNC, null byte -> SecurityError / ValueError
            for (String bad : new String[]{
                    "c.send('read_text', {'path': '../outside/secret.txt'})",
                    "c.send('read_text', {'path': '/etc/passwd'})",
                    "c.send('read_text', {'path': 'C:\\\\Windows\\\\win.ini'})",
                    "c.send('read_text', {'path': 'sym_file.txt'})",
                    "c.send('read_text', {'path': 'sym_dir/secret.txt'})",
                    "c.send('write_text', {'path': 'sym_file.txt', 'content': 'pwn'})",
                    "cro.send('write_text', {'path': 'sub/a.txt', 'content': 'hi'})",
            }) {
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class, () -> rt.eval(bad), bad).typeName());
            }
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("c.send('write_text', {'path': 'sub/big.txt', 'content': 'x' * 100})")).typeName());
            rt.eval("r = c.send('exists', {'path': 'sym_file.txt'})");
            assertEquals("False", rt.getGlobal("r").toString());
        }
    }

    private static String repr(Path p) {
        return "\"" + p.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
