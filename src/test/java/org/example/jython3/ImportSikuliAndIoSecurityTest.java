package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security tests for .sikuli bundle imports, importlib.reload symlink/policy
 * enforcement, and io.open() closed-file, symlink, size-bound, and concurrency rules.
 */
class ImportSikuliAndIoSecurityTest {

    @Test
    @DisplayName(".sikuli symlink bundles and symlinked reload targets are blocked with SecurityError")
    void sikuliAndReloadSymlinkEscapesBlocked(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("mods"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("evil.py"), "SECRET = 999\n");

        // 1. Symlinked .sikuli directory pointing outside root
        Path sikuliLink = root.resolve("bad_bundle.sikuli");
        boolean symlinksOk = false;
        try {
            Files.createSymbolicLink(sikuliLink, outside);
            symlinksOk = true;
        } catch (UnsupportedOperationException ignored) {}

        // 2. Real .sikuli bundle with clean initial module, then swapped with symlink before reload()
        Path goodBundle = Files.createDirectory(root.resolve("good_bundle.sikuli"));
        Path goodScript = Files.writeString(goodBundle.resolve("good_bundle.py"), "VAL = 1\n");

        try (var interp = Jython3Interpreter.trusted()) {
            interp.addModulePath(root);
            if (symlinksOk) {
                Files.writeString(outside.resolve("bad_bundle.py"), "X = 1\n");
                InterpreterException err = assertThrows(InterpreterException.class,
                        () -> interp.eval("import bad_bundle"));
                assertEquals("SecurityError", err.typeName());
            }

            interp.eval("import importlib, good_bundle\nassert good_bundle.VAL == 1");
            if (symlinksOk) {
                Files.delete(goodScript);
                Files.createSymbolicLink(goodScript, outsideFile);
                InterpreterException reloadErr = assertThrows(InterpreterException.class,
                        () -> interp.eval("importlib.reload(good_bundle)"));
                assertEquals("SecurityError", reloadErr.typeName());
                // Old module remains intact (not wiped on failed reload)
                interp.eval("assert good_bundle.VAL == 1");
            }

            // Built-in modules can be reloaded without touching disk
            interp.eval("import itertools, threading\nimportlib.reload(itertools)\nimportlib.reload(threading)");
        }

        // 3. Under DENY_ALL policy, sys.path append cannot load .sikuli bundles
        try (var sandboxed = new Jython3Interpreter(DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED)) {
            String escapedRoot = root.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
            InterpreterException err = assertThrows(InterpreterException.class, () -> sandboxed.eval(
                    "import sys\nsys.path.append(\"" + escapedRoot + "\")\nimport good_bundle\n"));
            assertTrue("ImportError".equals(err.typeName()) || "SecurityError".equals(err.typeName()));
        }
    }

    @Test
    @DisplayName("io.open() enforces closed-file ValueError, symlink rejection, size caps, and concurrent Virtual Thread writes")
    void ioOpenSecurityAndConcurrency(@TempDir Path tmp) throws Exception {
        Path logFile = tmp.resolve("app.log");
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "SECRET");
        Path link = tmp.resolve("link.txt");
        boolean symlinksOk = false;
        try {
            Files.createSymbolicLink(link, outside);
            symlinksOk = true;
        } catch (UnsupportedOperationException ignored) {}

        var tightLimits = new ExecutionLimits(500_000, Duration.ofSeconds(3), 50_000, 1_000, 512, 100);
        try (var interp = new Jython3Interpreter(AllowlistPolicy.trusted(), HostAccessPolicy.PERMISSIVE, tightLimits,
                org.example.jython3.runtime.ExceptionTypeRegistry.standard(),
                org.example.jython3.host.CapabilitySet.of(org.example.jython3.host.HostCapability.THREADS))) {

            String pStr = logFile.toAbsolutePath().toString().replace("\\", "\\\\");
            interp.eval("""
                import io, threading
                with io.open("%s", "w") as f:
                    f.write("init\\n")
                # Writing after 'with' exit must raise ValueError
                closed_rejected = False
                try:
                    f.write("more")
                except ValueError:
                    closed_rejected = True

                # Concurrent Virtual Thread writes to same file handle stay well-formed and complete
                fh = io.open("%s", "a")
                def writer(tid):
                    for j in range(5):
                        fh.write("t" + str(tid) + ":" + str(j) + "\\n")
                ts = [threading.Thread(target=writer, args=(i,)) for i in range(4)]
                for t in ts: t.start()
                for t in ts: t.join()
                content = fh.read()
                fh.close()
                line_count = len(content.strip().splitlines())
                """.formatted(pStr, pStr));
            assertEquals(PyBool.TRUE, interp.getGlobal("closed_rejected"));
            assertEquals("21", interp.getGlobal("line_count").toString());

            // Cumulative file size exceeds maxStringLength (512) inside IoModule
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> interp.eval("""
                        import io
                        with io.open("%s", "w") as bf:
                            bf.write("x" * 400)
                            bf.write("y" * 400)
                        """.formatted(pStr))).typeName());

            // Symlink open blocked
            if (symlinksOk) {
                String lStr = link.toAbsolutePath().toString().replace("\\", "\\\\");
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class, () -> interp.eval("import io\nio.open(\"" + lStr + "\", 'r')"))
                                .typeName());
            }
        }
    }
}
