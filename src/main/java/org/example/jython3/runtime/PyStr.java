package org.example.jython3.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Python Unicode string object, operating strictly on Unicode code points.
 * Note: Grapheme clusters (combining characters) are represented as sequences
 * of code points, matching standard CPython semantics.
 */
public record PyStr(String value) implements PyObject {

    @Override
    public String toString() {
        return value;
    }

    /**
     * Returns an array of Unicode code points for this string.
     */
    public int[] codePoints() {
        return value.codePoints().toArray();
    }

    /**
     * Returns the length of this string in Unicode code points (matching Python len(str)).
     */
    public int codePointCount() {
        return value.codePointCount(0, value.length());
    }

    /**
     * Returns the single-codepoint substring at the given index (in code points).
     * Supports negative indexing.
     *
     * @throws InterpreterException IndexError if index is out of bounds
     */
    public String getCodePointAt(int index) {
        int[] cps = codePoints();
        if (index < 0) index += cps.length;
        if (index < 0 || index >= cps.length) {
            throw PyErrors.error("IndexError", "string index out of range");
        }
        return new String(Character.toChars(cps[index]));
    }

    /**
     * Converts this string into a list of single code-point PyStr objects,
     * matching Python's string iteration and list(str) semantics.
     */
    public List<PyObject> toCodePointList() {
        var list = new ArrayList<PyObject>();
        value.codePoints().forEach(cp -> list.add(new PyStr(new String(Character.toChars(cp)))));
        return list;
    }

    /**
     * Returns a PyIterator over the Unicode code points of this string.
     */
    public PyIterator codePointIterator() {
        return new PyIterator(toCodePointList(), 0);
    }
}
