package org.example.jython3.runtime;

import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Immutable Python 3 {@code bytes} sequence.
 */
public final class PyBytes implements PyObject {

    public static final PyBytes EMPTY = new PyBytes(new byte[0]);

    private final byte[] data;
    private int cachedHash;
    private boolean hashComputed;

    public PyBytes(byte[] data) {
        this.data = data == null ? new byte[0] : data.clone();
    }

    /** Internal constructor taking ownership of an already-private array. */
    public static PyBytes wrapOwned(byte[] owned) {
        return owned.length == 0 ? EMPTY : new PyBytes(owned, true);
    }

    private PyBytes(byte[] owned, boolean ignored) {
        this.data = owned;
    }

    public byte[] bytesUnsafe() { return data; }

    public int length() { return data.length; }

    public PyObject getAt(PyObject idxObj) {
        int i = extractIntIndex(idxObj);
        if (i < 0) i += data.length;
        if (i < 0 || i >= data.length) {
            throw PyErrors.error("IndexError", "index out of range");
        }
        return PyInt.of(data[i] & 0xFF);
    }

    public PyBytes slice(PySlice sl) {
        PySlice.Indices idx = sl.computeIndices(data.length);
        byte[] out = new byte[idx.length()];
        for (int i = idx.start(), c = 0; c < idx.length(); i += idx.step(), c++) {
            out[c] = data[i];
        }
        return wrapOwned(out);
    }

    public boolean contains(PyObject item) {
        if (item instanceof PyInt || item instanceof PyBool || item instanceof BigInt) {
            int b = extractByteValue(item);
            for (byte v : data) {
                if ((v & 0xFF) == b) return true;
            }
            return false;
        }
        byte[] needle = extractBuffer(item);
        return indexOfBytes(data, needle) >= 0;
    }

    public String decode(String encoding, String errors) {
        Charset cs = resolveCharset(encoding);
        if ("ignore".equals(errors)) {
            var dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE);
            try {
                return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
            } catch (Exception e) {
                throw PyErrors.error("UnicodeDecodeError", e.getMessage());
            }
        }
        if ("replace".equals(errors)) {
            var dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
            } catch (Exception e) {
                throw PyErrors.error("UnicodeDecodeError", e.getMessage());
            }
        }
        var dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (Exception e) {
            throw PyErrors.error("UnicodeDecodeError", "'" + cs.name() + "' codec can't decode bytes");
        }
    }

    public String toHex() {
        var sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] parseHex(String hex) {
        String clean = hex.replaceAll("\\s+", "");
        if ((clean.length() & 1) != 0) {
            throw PyErrors.error("ValueError", "non-hexadecimal number found in fromhex() arg");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw PyErrors.error("ValueError", "non-hexadecimal number found in fromhex() arg");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public PyIterator iterator() {
        return byteIterator(data);
    }

    static PyIterator byteIterator(byte[] buf) {
        return new PyIterator(new Iterator<PyObject>() {
            private int idx = 0;
            @Override public boolean hasNext() { return idx < buf.length; }
            @Override public PyObject next() {
                if (idx >= buf.length) throw new NoSuchElementException();
                return PyInt.of(buf[idx++] & 0xFF);
            }
        });
    }

    static int extractIntIndex(PyObject idxObj) {
        if (idxObj instanceof PyInt i) return i.value();
        if (idxObj instanceof PyBool b) return b.value() ? 1 : 0;
        if (idxObj instanceof BigInt bi) {
            try { return bi.value().intValueExact(); }
            catch (ArithmeticException ae) { throw PyErrors.error("IndexError", "cannot fit 'int' into an index-sized integer"); }
        }
        throw PyErrors.error("TypeError", "byte indices must be integers or slices, not " + PyOps.typeName(idxObj));
    }

    public static int extractByteValue(PyObject o) {
        long v;
        if (o instanceof PyInt i) v = i.value();
        else if (o instanceof PyBool b) v = b.value() ? 1 : 0;
        else if (o instanceof BigInt bi) {
            if (bi.value().signum() < 0 || bi.value().compareTo(java.math.BigInteger.valueOf(255)) > 0) {
                throw PyErrors.error("ValueError", "byte must be in range(0, 256)");
            }
            return bi.value().intValue();
        } else {
            throw PyErrors.error("TypeError", "'" + PyOps.typeName(o) + "' object cannot be interpreted as an integer");
        }
        if (v < 0 || v > 255) {
            throw PyErrors.error("ValueError", "byte must be in range(0, 256)");
        }
        return (int) v;
    }

    public static byte[] extractBuffer(PyObject o) {
        if (o instanceof PyBytes b) return b.data;
        if (o instanceof PyByteArray ba) return ba.copyBytes();
        throw PyErrors.error("TypeError", "a bytes-like object is required, not '" + PyOps.typeName(o) + "'");
    }

    public static int indexOfBytes(byte[] hay, byte[] needle) {
        if (needle.length == 0) return 0;
        if (needle.length > hay.length) return -1;
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public static Charset resolveCharset(String encoding) {
        if (encoding == null) return StandardCharsets.UTF_8;
        String norm = encoding.trim().toLowerCase().replace('_', '-');
        return switch (norm) {
            case "utf-8", "utf8" -> StandardCharsets.UTF_8;
            case "ascii", "us-ascii" -> StandardCharsets.US_ASCII;
            case "latin-1", "latin1", "iso-8859-1" -> StandardCharsets.ISO_8859_1;
            case "utf-16", "utf16" -> StandardCharsets.UTF_16;
            case "utf-16le", "utf-16-le" -> StandardCharsets.UTF_16LE;
            case "utf-16be", "utf-16-be" -> StandardCharsets.UTF_16BE;
            default -> {
                try {
                    yield Charset.forName(encoding);
                } catch (Exception e) {
                    throw PyErrors.error("LookupError", "unknown encoding: " + encoding);
                }
            }
        };
    }

    public static String formatBytesRepr(byte[] data, String prefix, String suffix) {
        var sb = new StringBuilder(prefix.length() + data.length + suffix.length() + 4);
        sb.append(prefix);
        // Pick quote matching CPython: single quote unless ' present and " absent
        boolean hasSingle = false, hasDouble = false;
        for (byte raw : data) {
            if (raw == '\'') hasSingle = true;
            else if (raw == '"') hasDouble = true;
        }
        char q = (hasSingle && !hasDouble) ? '"' : '\'';
        sb.append(q);
        for (byte raw : data) {
            int b = raw & 0xFF;
            switch (b) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (b == q) sb.append('\\').append(q);
                    else if (b >= 0x20 && b < 0x7F) sb.append((char) b);
                    else sb.append(String.format("\\x%02x", b));
                }
            }
        }
        return sb.append(q).append(suffix).toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof PyBytes other) return Arrays.equals(this.data, other.data);
        if (o instanceof PyByteArray other) return Arrays.equals(this.data, other.copyBytes());
        return false;
    }

    @Override
    public int hashCode() {
        if (!hashComputed) {
            cachedHash = Arrays.hashCode(data);
            hashComputed = true;
        }
        return cachedHash;
    }

    @Override
    public String toString() {
        return formatBytesRepr(data, "b", "");
    }
}
