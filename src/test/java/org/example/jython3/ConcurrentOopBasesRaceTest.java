package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.security.ExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies PyClass.getBases() immutability and thread-safe concurrent
 * __bases__ mutation with LOAD_ATTR inline caches across Virtual Threads.
 */
class ConcurrentOopBasesRaceTest {

    @Test
    @DisplayName("PyClass.getBases() returns an immutable copy that cannot bypass C3 MRO validation")
    void pyClassGetBasesIsImmutable() {
        PyClass base = new PyClass("Base", null, Map.of());
        PyClass sub = new PyClass("Sub", base, Map.of());
        assertThrows(UnsupportedOperationException.class, () -> sub.getBases().add(base));
        assertThrows(UnsupportedOperationException.class, () -> sub.getBases().clear());
    }

    @Test
    @DisplayName("Concurrent __bases__ mutation and attribute/isinstance reads remain consistent without races")
    void concurrentDynamicBasesMutation() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(6).build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build()) {
            rt.eval("""
                import threading

                class A:
                    val = "A"
                    def get_tag(self):
                        return "A"

                class B:
                    val = "B"
                    def get_tag(self):
                        return "B"

                class Dyn(A):
                    pass

                inst = Dyn()
                errors = []
                stop = threading.Event()

                def mutator():
                    try:
                        for i in range(250):
                            Dyn.__bases__ = (B,) if (i % 2 == 0) else (A,)
                    except Exception as e:
                        errors.append(str(e))
                    finally:
                        stop.set()

                def reader():
                    try:
                        while not stop.is_set():
                            v = inst.val
                            t = inst.get_tag()
                            if v not in ("A", "B") or t not in ("A", "B"):
                                errors.append("bad:" + str(v) + "/" + str(t))
                            _ = isinstance(inst, (A, B))
                    except Exception as e:
                        errors.append(str(e))

                t_mut = threading.Thread(target=mutator)
                readers = [threading.Thread(target=reader) for _ in range(3)]
                for r in readers:
                    r.start()
                t_mut.start()
                t_mut.join()
                for r in readers:
                    r.join()

                # Final deterministic check after mutation settles
                Dyn.__bases__ = (B,)
                final_ok = (inst.val == "B" and inst.get_tag() == "B" and isinstance(inst, B) and not isinstance(inst, A))
                no_errors = (len(errors) == 0)
                """);
            assertEquals(PyBool.TRUE, rt.getGlobal("no_errors"), "Errors: " + rt.getGlobal("errors"));
            assertEquals(PyBool.TRUE, rt.getGlobal("final_ok"));
            assertEquals(0, rt.hostSession().activeThreadCount());
        }
    }
}
