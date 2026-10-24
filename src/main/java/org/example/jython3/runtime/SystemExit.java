package org.example.jython3.runtime;

public class SystemExit extends RuntimeException {
    public final int code;
    public SystemExit(int code) { super("SystemExit: " + code); this.code = code; }
    public SystemExit() { this(0); }
}
