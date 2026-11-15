package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.ScopedWorkspaceAdapter;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cross-platform workspace containment tests covering Windows-style separators,
 * drive/UNC rejections, Unicode filenames, and non-SecureDirectoryStream fallback
 * filesystems (via JDK ZipFileSystem).
 */
class CrossPlatformWorkspaceTest {

    @Test
    @DisplayName("Cross-platform path rules normalize backslashes and block Windows drive/UNC/traversal forms")
    void crossPlatformPathNormalizationAndBlocks(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectory(tmp.resolve("ws"));
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM)
                .allowDevice("ws")
                .allowCommand("write_text", "read_text", "list_dir", "exists")
                .build();

        try (var rt = EmbeddedRuntime.builder().capabilities(caps).build()) {
            rt.registerDevice(new ScopedWorkspaceAdapter("ws", ws, rt.hostSession(), false));
            rt.eval("""
                import control
                ws = control.Controller("ws")
                ws.send("write_text", {"path": "nested\\\\hello.txt", "content": "مرحبا-Oculix-🚀"})
                assert ws.send("exists", {"path": "nested/hello.txt"}) == True
                assert ws.send("read_text", {"path": "./nested/hello.txt"}) == "مرحبا-Oculix-🚀"
                ws.send("write_text", {"path": "empty.txt", "content": ""})
                assert ws.send("read_text", {"path": "empty.txt"}) == ""
                assert ws.send("list_dir", {"path": "nested"}) == ["hello.txt"]
                ok = True
                """);
            assertEquals(PyBool.TRUE, rt.getGlobal("ok"));

            for (String attack : new String[]{
                    "..\\\\outside.txt",
                    "nested/../../outside.txt",
                    "C:secret.txt",
                    "C:\\\\Windows\\\\win.ini",
                    "\\\\\\\\server\\\\share\\\\file.txt",
                    "/etc/passwd"
            }) {
                InterpreterException ex = assertThrows(InterpreterException.class,
                        () -> rt.eval("control.Controller('ws').send('exists', {'path': '" + attack + "'})"),
                        "Expected SecurityError for path: " + attack);
                assertEquals("SecurityError", ex.typeName());
            }
        }
    }

    @Test
    @DisplayName("Fallback path works securely on filesystems without SecureDirectoryStream (ZipFileSystem)")
    void fallbackNonSecureDirectoryStreamFilesystem(@TempDir Path tmp) throws Exception {
        Path zipFile = tmp.resolve("workspace.zip");
        URI zipUri = URI.create("jar:" + zipFile.toUri());
        try (FileSystem zipFs = FileSystems.newFileSystem(zipUri, Map.of("create", "true"))) {
            Path root = Files.createDirectory(zipFs.getPath("/ws"));
            Files.createDirectory(root.resolve("outside"));
            Files.writeString(zipFs.getPath("/outside_secret.txt"), "NOPE");

            var caps = CapabilitySet.builder()
                    .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM)
                    .allowDevice("zipws")
                    .allowCommand("write_text", "read_text", "list_dir", "exists")
                    .build();

            try (var rt = EmbeddedRuntime.builder().capabilities(caps).build()) {
                rt.registerDevice(new ScopedWorkspaceAdapter("zipws", root, rt.hostSession(), false));
                rt.eval("""
                    import control
                    ws = control.Controller("zipws")
                    ws.send("write_text", {"path": "sub/note.txt", "content": "zip-ok"})
                    assert ws.send("exists", {"path": "sub/note.txt"}) == True
                    assert ws.send("read_text", {"path": "sub/note.txt"}) == "zip-ok"
                    assert "note.txt" in ws.send("list_dir", {"path": "sub"})
                    ok = True
                    """);
                assertEquals(PyBool.TRUE, rt.getGlobal("ok"));

                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class,
                                () -> rt.eval("control.Controller('zipws').send('read_text', {'path': '../outside_secret.txt'})"))
                                .typeName());
            }
        }
    }
}
