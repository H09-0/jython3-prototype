package org.example.jython3.ui;

import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyStr;

/**
 * Immutable UI event passed to Python UI event callbacks.
 */
public record UiEvent(String type, String sourceId, String text, long timestampMs) {

    public PyHostHandle toPyHandle() {
        return PyHostHandle.builder("ui.Event")
                .prop("type", () -> new PyStr(type))
                .prop("source_id", () -> new PyStr(sourceId))
                .prop("text", () -> new PyStr(text != null ? text : ""))
                .prop("timestamp", () -> PyInt.of((int) (timestampMs & 0x7FFFFFFFL)))
                .build();
    }
}
