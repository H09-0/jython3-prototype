package org.example.jython3.field;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;

/** JVM resource telemetry helper for RAM, Metaspace, CPU, GC, and Thread monitoring. */
public final class ResourceProfiler {
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private ResourceProfiler() {}

    public static long processCpuNanos() {
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            return sunOs.getProcessCpuTime();
        }
        return -1;
    }

    public static long gcCount() {
        long count = 0;
        for (var gc : GC_BEANS) {
            long c = gc.getCollectionCount();
            if (c > 0) count += c;
        }
        return count;
    }

    public static long gcTimeMs() {
        long time = 0;
        for (var gc : GC_BEANS) {
            long t = gc.getCollectionTime();
            if (t > 0) time += t;
        }
        return time;
    }

    public static double usedHeapMB() {
        return MEMORY_BEAN.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
    }

    public static double usedNonHeapMB() {
        return MEMORY_BEAN.getNonHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
    }

    public static int threadCount() {
        return THREAD_BEAN.getThreadCount();
    }
}
