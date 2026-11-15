package org.example.jython3.host;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, thread-safe audit trail recording every allowed and denied host
 * capability check and control command.
 */
public final class HostAuditLog {

    public record AuditEntry(
            Instant timestamp,
            HostCapability capability,
            String action,
            String target,
            boolean allowed,
            String detail
    ) {}

    private static final int MAX_ENTRIES = 1024;
    private final Deque<AuditEntry> entries = new ArrayDeque<>(64);
    private long allowedCount = 0;
    private long deniedCount = 0;

    public synchronized void recordAllowed(HostCapability cap, String action, String target, String detail) {
        allowedCount++;
        append(new AuditEntry(Instant.now(), cap, sanitizeLogField(action),
                sanitizeLogField(target), true, sanitizeLogField(detail)));
    }

    public synchronized void recordDenied(HostCapability cap, String action, String target, String detail) {
        deniedCount++;
        append(new AuditEntry(Instant.now(), cap, sanitizeLogField(action),
                sanitizeLogField(target), false, sanitizeLogField(detail)));
    }

    /** Neutralizes CR/LF and control characters in log fields (CWE-117 / CERT IDS03-J). */
    public static String sanitizeLogField(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                if (sb == null) {
                    sb = new StringBuilder(raw.length());
                    sb.append(raw, 0, i);
                }
                sb.append(' ');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : raw;
    }

    private void append(AuditEntry entry) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    public synchronized List<AuditEntry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized long allowedCount() { return allowedCount; }

    public synchronized long deniedCount() { return deniedCount; }

    public synchronized void clear() {
        entries.clear();
        allowedCount = 0;
        deniedCount = 0;
    }
}
