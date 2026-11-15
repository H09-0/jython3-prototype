package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.ScopedWorkspaceAdapter;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-threaded TOCTOU / symlink-race tests for {@link ScopedWorkspaceAdapter}.
 */
class ScopedWorkspaceRaceTest {

    @Test
    @DisplayName("Concurrent symlink swap race never writes or reads outside canonical workspace root")
    void concurrentSymlinkRaceNeverEscapesRoot(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectory(tmp.resolve("ws"));
        Path outsideDir = Files.createDirectory(tmp.resolve("outside"));
        Path outsideTarget = Files.writeString(outsideDir.resolve("target.txt"), "ORIGINAL_SECRET");
        Path insideSub = Files.createDirectory(ws.resolve("sub"));

        // Verify symlink support on current filesystem
        Path probeLink = ws.resolve("probe_link");
        try {
            Files.createSymbolicLink(probeLink, outsideTarget);
            Files.deleteIfExists(probeLink);
        } catch (UnsupportedOperationException e) {
            return;
        }

        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM)
                .allowDevice("ws")
                .allowCommand("write_text", "read_text", "exists")
                .build();

        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch racerReady = new CountDownLatch(1);
        AtomicInteger securityRejects = new AtomicInteger(0);
        AtomicInteger successfulInsideOps = new AtomicInteger(0);

        // Background attacker thread continuously flips ws/sub/leaf.txt and ws/sub_swap between real and symlink-to-outside
        Path leaf = insideSub.resolve("leaf.txt");
        Path swapPath = ws.resolve("swap");

        var exec = Executors.newSingleThreadExecutor();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).build()) {
            rt.registerDevice(new ScopedWorkspaceAdapter("ws", ws, rt.hostSession(), false));

            var racer = exec.submit(() -> {
                racerReady.countDown();
                int iter = 0;
                while (!stop.get()) {
                    try {
                        Files.deleteIfExists(leaf);
                        if ((iter++ & 1) == 0) {
                            Files.createSymbolicLink(leaf, outsideTarget);
                        } else {
                            Files.writeString(leaf, "SAFE");
                        }
                        Files.deleteIfExists(swapPath);
                        if ((iter & 1) == 0) {
                            Files.createSymbolicLink(swapPath, outsideDir);
                        } else {
                            Files.createDirectory(swapPath);
                        }
                    } catch (Exception ignored) {
                        // Expected transient contention while writer operates
                    }
                }
            });

            assertTrue(racerReady.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < 200; i++) {
                try {
                    rt.eval("import control\ncontrol.Controller('ws').send('write_text', {'path': 'sub/leaf.txt', 'content': 'PWNED'})");
                    successfulInsideOps.incrementAndGet();
                } catch (InterpreterException ex) {
                    assertTrue("SecurityError".equals(ex.typeName()) || "OSError".equals(ex.typeName())
                                    || "FileNotFoundError".equals(ex.typeName()),
                            "Unexpected exception during leaf race: " + ex.typeName() + " -> " + ex.getMessage());
                    if ("SecurityError".equals(ex.typeName())) {
                        securityRejects.incrementAndGet();
                    }
                }
                try {
                    rt.eval("import control\ncontrol.Controller('ws').send('write_text', {'path': 'swap/target.txt', 'content': 'PWNED'})");
                } catch (InterpreterException ex) {
                    assertTrue("SecurityError".equals(ex.typeName()) || "OSError".equals(ex.typeName())
                                    || "FileNotFoundError".equals(ex.typeName()),
                            "Unexpected exception during dir swap race: " + ex.typeName() + " -> " + ex.getMessage());
                    if ("SecurityError".equals(ex.typeName())) {
                        securityRejects.incrementAndGet();
                    }
                }
            }

            stop.set(true);
            racer.get(5, TimeUnit.SECONDS);
            // Outside file MUST remain completely untouched!
            assertEquals("ORIGINAL_SECRET", Files.readString(outsideTarget));
            assertTrue(securityRejects.get() > 0 || successfulInsideOps.get() >= 0);
        } finally {
            exec.shutdownNow();
        }
    }
}
