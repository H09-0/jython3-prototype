package org.example.jython3.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * A Python-level error. Carries the source location where it was raised and,
 * as the VM unwinds, one entry per frame that the error passed through.
 */
public class InterpreterException extends RuntimeException {
    private String file;
    private int line;
    private String function;
    private final List<TraceEntry> traceback = new ArrayList<>();

    public InterpreterException(String m) { super(m); }

    /** One frame in a traceback, ordered from outermost to innermost. */
    public record TraceEntry(String file, int line, String function) {}

    /** Records where the error was raised, unless a location is already set. */
    public void setLocation(String file, int line, String function) {
        if (this.line > 0) return;
        this.file = file;
        this.line = line;
        this.function = function;
    }

    /** Adds the next frame as the error unwinds. */
    public void addFrame(String file, int line, String function) {
        traceback.add(new TraceEntry(file, line, function));
    }

    public String file() { return file; }
    public int line() { return line; }
    public String function() { return function; }
    public List<TraceEntry> traceback() { return traceback; }
}
