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
        append(new AuditEntry(Instant.now(), cap, action, target, true, detail != null ? detail : ""));
    }

    public synchronized void recordDenied(HostCapability cap, String action, String target, String detail) {
        deniedCount++;
        append(new AuditEntry(Instant.now(), cap, action, target, false, detail != null ? detail : ""));
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
