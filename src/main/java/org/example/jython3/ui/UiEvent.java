package org.example.jython3.ui;

import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyStr;

import java.util.Objects;

/**
 * Immutable UI event passed to Python UI event callbacks.
 */
public record UiEvent(String type, String sourceId, String text, long timestampMs) {

    public PyHostHandle toPyHandle(HostSession session) {
        Objects.requireNonNull(session, "session");
        return PyHostHandle.builder("ui.Event")
                .bindSession(session)
                .prop("type", () -> new PyStr(type))
                .prop("source_id", () -> new PyStr(sourceId))
                .prop("text", () -> new PyStr(text != null ? text : ""))
                .prop("timestamp", () -> PyInt.of((int) (timestampMs & 0x7FFFFFFFL)))
                .build();
    }
}
