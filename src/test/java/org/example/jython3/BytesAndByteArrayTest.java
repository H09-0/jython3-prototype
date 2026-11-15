package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.security.ExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for Track 2B: bytes and bytearray (literals, constructors, indexing/slicing,
 * decode/encode/hex/fromhex, mutation, hashing, equality, and ExecutionLimits DoS checks).
 */
class BytesAndByteArrayTest {

    @Test
    @DisplayName("bytes and bytearray semantics match CPython across literals, methods, slicing, and hashing")
    void bytesAndByteArrayMatchCPython() {
        String code = """
            b = b"hello\\x00world"
            print(len(b), b[0], b[-1], b[1:5], list(b[:4]))
            print(b"wor" in b, 104 in b, b.startswith(b"hel"), b.endswith(b"rld"), b.find(b"lo"), b.count(b"l"))
            print(b.hex(), bytes.fromhex("deadbeef") == b"\\xde\\xad\\xbe\\xef")

            u = "مرحبا 🚀"
            enc = u.encode("utf-8")
            print(isinstance(enc, bytes), enc.decode("utf-8") == u)
            print(bytes(4), bytes([65, 66, 67]), bytes("hi", "ascii"))

            ba = bytearray(b"abc")
            print(ba == b"abc", hash(b"abc") == hash(bytes([97, 98, 99])))
            ba[0] = 65
            ba.append(68)
            ba.extend(b"EF")
            ba += b"!"
            print(ba, len(ba), ba.decode("ascii"))
            ba[1:3] = b"XYZ"
            print(ba.decode("ascii"), ba.pop(), ba.decode("ascii"))
            ba.reverse()
            print(ba.decode("ascii"))

            d = {b"k": 123}
            print(d[b"k"])
            try:
                _ = {bytearray(b"k"): 1}
                print("UNEXPECTED_HASHABLE")
            except TypeError:
                print("BYTEARRAY_UNHASHABLE")
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("bytes/bytearray enforce ExecutionLimits against memory amplification DoS and invalid byte ranges")
    void bytesSecurityBounds() {
        var tight = new ExecutionLimits(50_000, Duration.ofSeconds(2), 50_000, 500, 500, 100);
        try (var rt = EmbeddedRuntime.builder().limits(tight).build()) {
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("bytes(10_000)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("b'a' * 1000")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("b = b'a' * 300\nb + b")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("ba = bytearray(b'a' * 300)\nba += b'a' * 300")).typeName());
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class, () -> rt.eval("ba = bytearray(b'a')\nba[0] = 256")).typeName());
            assertEquals("SyntaxError",
                    assertThrows(InterpreterException.class, () -> rt.eval("b'مرحبا'")).typeName());
        }
    }
}
