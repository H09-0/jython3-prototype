package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.SandboxedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Policy-aware file module loader tests: search paths, dotted packages,
 * __init__.py package boundaries, symlink-escape & path-traversal protection,
 * circular imports, sys.modules/sys.path, and pure-Python stdlib modules.
 */
class ImportSystemTest {

    @Test
    @DisplayName("Dotted packages, aliases, from-import, __all__, __name__/__file__, and package boundaries")
    void dottedPackagesAndMetadata(@TempDir Path tempDir) throws Exception {
        Path pkg = tempDir.resolve("mypkg");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("__init__.py"),
                "pkg_tag = 'mypkg-init'\nmod_name = __name__\npkg_name = __package__\n");
        Files.writeString(pkg.resolve("sub.py"),
                "__all__ = ['public_fn']\n"
                + "def public_fn(x):\n    return x * 2\n"
                + "def _private_fn():\n    return 99\n"
                + "sub_name = __name__\nsub_pkg = __package__\nsub_file = __file__\n");

        // Directory without __init__.py must NOT act as a package
        Path bareDir = tempDir.resolve("notapkg");
        Files.createDirectories(bareDir);
        Files.writeString(bareDir.resolve("mod.py"), "v = 1\n");

        var interp = Jython3Interpreter.trusted();
        interp.addModulePath(tempDir);

        interp.eval("""
            import sys
            import mypkg.sub
            assert mypkg.pkg_tag == "mypkg-init"
            assert mypkg.mod_name == "mypkg"
            assert mypkg.pkg_name == "mypkg"
            assert mypkg.sub.public_fn(21) == 42
            assert mypkg.sub.sub_name == "mypkg.sub"
            assert mypkg.sub.sub_pkg == "mypkg"
            assert "mypkg.sub" in sys.modules
            assert "mypkg" in sys.modules

            import mypkg.sub as aliased
            assert aliased is mypkg.sub

            from mypkg.sub import public_fn as pfn
            assert pfn(5) == 10

            from mypkg import sub as sub_mod
            assert sub_mod is mypkg.sub

            from mypkg.sub import *
            assert public_fn(3) == 6
            try:
                x = _private_fn()
                leaked = True
            except NameError:
                leaked = False
            assert not leaked

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));

        // Non-package subdirectory rejected with ImportError
        var noInit = assertThrows(InterpreterException.class, () -> interp.eval("import notapkg.mod"));
        assertEquals("ImportError", noInit.typeName());
    }

    @Test
    @DisplayName("Symlink escape, directory symlink traversal, and HostAccessPolicy restrictions are blocked")
    void symlinkEscapeAndPolicyIsolation(@TempDir Path tempDir) throws Exception {
        Path modRoot = tempDir.resolve("mods");
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(modRoot);
        Files.createDirectories(outsideDir);

        Path secret = outsideDir.resolve("secret.py");
        Files.writeString(secret, "pwned = True\n");

        Path symlinkFile = modRoot.resolve("evil.py");
        Files.createSymbolicLink(symlinkFile, secret);

        Path symlinkPkg = modRoot.resolve("evilpkg");
        Files.createSymbolicLink(symlinkPkg, outsideDir);
        Files.writeString(outsideDir.resolve("__init__.py"), "pwned = True\n");

        var interp = Jython3Interpreter.trusted();
        interp.addModulePath(modRoot);

        var e1 = assertThrows(InterpreterException.class, () -> interp.eval("import evil"));
        assertEquals("SecurityError", e1.typeName());

        var e2 = assertThrows(InterpreterException.class, () -> interp.eval("import evilpkg.secret"));
        assertEquals("SecurityError", e2.typeName());

        // HostAccessPolicy.DENY_ALL blocks disk module imports while allowing math/json
        var noDisk = new Jython3Interpreter(AllowlistPolicy.trusted(), HostAccessPolicy.DENY_ALL, ExecutionLimits.TRUSTED);
        noDisk.addModulePath(modRoot);
        Files.writeString(modRoot.resolve("legit.py"), "x = 1\n");
        var e3 = assertThrows(InterpreterException.class, () -> noDisk.eval("import legit"));
        assertEquals("SecurityError", e3.typeName());

        noDisk.eval("import math, json\nr = int(math.sqrt(16)) + json.loads('{\"a\": 1}')['a']");
        assertEquals("5", noDisk.getGlobal("r").toString());

        // SandboxedPolicy blocks collections.py on disk because DENY_ALL forbids disk import
        var sandboxed = new Jython3Interpreter(SandboxedPolicy.get());
        var e4 = assertThrows(InterpreterException.class, () -> sandboxed.eval("import collections"));
        assertEquals("SecurityError", e4.typeName());
    }

    @Test
    @DisplayName("Circular from-import failure reports clear ImportError and cleans cache")
    void circularFromImportCleansCache(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("ca.py"), "from cb import needed\nx = 1\n");
        Files.writeString(tempDir.resolve("cb.py"), "from ca import x\nneeded = 2\n");

        var interp = Jython3Interpreter.trusted();
        interp.addModulePath(tempDir);

        var e = assertThrows(InterpreterException.class, () -> interp.eval("import ca"));
        assertEquals("ImportError", e.typeName());
        assertTrue(e.getMessage().contains("partially initialized") || e.getMessage().contains("cannot import name"),
                e.getMessage());

        // Cache was cleaned: fixing ca.py allows re-import to succeed
        Files.writeString(tempDir.resolve("ca.py"), "x = 10\nfrom cb import needed\n");
        interp.eval("import ca\nok = ca.x + ca.needed");
        assertEquals("12", interp.getGlobal("ok").toString());
    }

    @Test
    @DisplayName("Pure-Python stdlib modules: collections and functools")
    void purePythonStdlibModules() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            from collections import Counter, defaultdict, deque
            from functools import reduce, partial, lru_cache

            c = Counter(["a", "b", "a", "c", "a", "b"])
            assert c.get("a") == 3
            assert c.get("missing") == 0
            assert c.total() == 6
            assert c.most_common(2) == [("a", 3), ("b", 2)]
            assert len(c) == 3

            dd = defaultdict(list)
            dd.get("k").append(1)
            dd.get("k").append(2)
            assert dd.get("k") == [1, 2]
            assert dd.get("empty") == []

            dq = deque([1, 2, 3], maxlen=3)
            dq.append(4)
            assert dq.to_list() == [2, 3, 4]
            dq.appendleft(9)
            assert dq.to_list() == [9, 2, 3]
            assert dq.pop() == 3
            assert dq.popleft() == 9

            assert reduce(lambda a, b: a + b, [1, 2, 3, 4]) == 10
            assert reduce(lambda a, b: a + b, [], 42) == 42
            try:
                reduce(lambda a, b: a + b, [])
                empty_err = False
            except TypeError:
                empty_err = True
            assert empty_err

            def add3(a, b, c=0):
                return a + b + c
            p = partial(add3, 10, c=5)
            assert p(2) == 17

            calls = [0]
            @lru_cache(maxsize=2)
            def sq(n):
                calls[0] += 1
                return n * n
            assert sq(3) == 9 and sq(3) == 9 and calls[0] == 1
            assert sq(4) == 16 and sq(5) == 25 and calls[0] == 3
            assert sq(3) == 9 and calls[0] == 4  # evicted from maxsize=2 cache

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }
}
