package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyFrozenSet;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PySet;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PyTuple;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.PolicySanitizer;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive tests covering unbound PyHostHandle rejection, cross-session smuggling
 * across every container/callable transport path, and stale-handle enforcement.
 */
class HandleSmugglingSecurityTest {

    @Test
    @DisplayName("PyHostHandle.Builder rejects building without a bound HostSession")
    void unboundHandleBuilderThrows() {
        var builder = PyHostHandle.builder("host.Secret")
                .prop("token", () -> new PyStr("secret-token"))
                .method("elevate", new PyBuiltinFunction("elevate", args -> PyNull.INSTANCE));
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("HostSession"));
    }

    @Test
    @DisplayName("Cross-session handle smuggling is rejected across direct, list, dict, tuple, set, module, closure, generator, and callback args")
    void crossSessionSmugglingAcrossAllVectors() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT)
                .build();
        AtomicBoolean secretInvoked = new AtomicBoolean(false);

        try (var hostA = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build();
             var hostB = EmbeddedRuntime.builder().uiToolkit(new HeadlessUiToolkit()).build()) {

            PyHostHandle secretA = PyHostHandle.builder("hostA.Vault")
                    .bindSession(hostA.hostSession())
                    .prop("secret", () -> new PyStr("TOP_SECRET"))
                    .method("unlock", new PyBuiltinFunction("unlock", a -> {
                        secretInvoked.set(true);
                        return new PyStr("unlocked");
                    }))
                    .build();
            hostA.bindHandle("vault", secretA);

            // Prepare closure & generator inside hostA that capture secretA
            hostA.eval("""
                def make_closure(h):
                    def inner():
                        return h.unlock()
                    return inner
                closure_with_handle = make_closure(vault)

                def gen_with_handle(h):
                    x = h
                    yield 1
                    yield x.unlock()
                gen_obj = gen_with_handle(vault)
                next(gen_obj)
                """);
            PyObject closureA = hostA.getGlobal("closure_with_handle");
            PyObject genA = hostA.getGlobal("gen_obj");
            assertTrue(genA instanceof PyGenerator);

            // 1. Direct bindHandle into hostB -> SecurityError + audit log
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> hostB.bindHandle("stolen", secretA)).typeName());
            assertTrue(hostB.auditEntries().stream().anyMatch(e -> !e.allowed() && "bind_handle".equals(e.action())));

            // 2. Direct bindModelData into hostB -> SecurityError
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> hostB.bindModelData("stolen_dto", secretA)).typeName());

            // 3. Nested containers & modules via PolicySanitizer / setGlobal -> SecurityError
            List<PyObject> vectors = List.of(
                    secretA,
                    new PyList(List.of(PyInt.ONE, secretA)),
                    new PyTuple(List.of(PyInt.ONE, secretA)),
                    new PySet(List.of(secretA)),
                    new PyFrozenSet(List.of(secretA)),
                    new PyDict(Map.of(new PyStr("k"), secretA)),
                    new PyDict(Map.of(secretA, new PyStr("v"))),
                    new PyModule("leak_mod", Map.of("h", secretA)),
                    closureA,
                    genA
            );
            for (PyObject vec : vectors) {
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class,
                                () -> PolicySanitizer.sanitize(vec, DenyAllPolicy.INSTANCE, hostB.hostSession()))
                                .typeName(),
                        "Vector should be rejected: " + vec.getClass().getSimpleName());
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class,
                                () -> hostB.bindModelData("smuggled", vec))
                                .typeName());
            }

            // 4. PolicySanitizer with null targetSession rejects any PyHostHandle
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> PolicySanitizer.sanitize(secretA, DenyAllPolicy.INSTANCE)).typeName());

            // 5. Callback argument smuggling via HostSession.invokeCallback -> SecurityError
            hostB.eval("def recv(x):\n    return x\n");
            PyObject recvB = hostB.getGlobal("recv");
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> hostB.hostSession().invokeCallback(recvB, secretA)).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> hostB.hostSession().invokeCallback(recvB, new PyList(List.of(secretA)))).typeName());

            assertFalse(secretInvoked.get(), "Smuggled handle method must never be invoked");
        }
    }

    @Test
    @DisplayName("Stale PyHostHandle fails on bindHandle, getAttr, setAttr, and method call after reset() and close()")
    void staleHandleAfterResetAndClose() {
        try (var rt = EmbeddedRuntime.builder().uiToolkit(new HeadlessUiToolkit()).build()) {
            PyHostHandle h = PyHostHandle.builder("oculix.Item")
                    .bindSession(rt.hostSession())
                    .prop("v", () -> PyInt.of(7), val -> {})
                    .method("ping", new PyBuiltinFunction("ping", a -> new PyStr("ok")))
                    .build();
            rt.bindHandle("item", h);
            rt.eval("assert item.v == 7 and item.ping() == 'ok'");

            // After restart(), old handle must fail everywhere
            rt.restart();
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> rt.bindHandle("item2", h)).typeName());
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> h.getAttr("v")).typeName());
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> h.setAttr("v", PyInt.ONE)).typeName());
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> h.getAttr("ping")).typeName());

            // Fresh handle in new epoch works, then fails after close()
            PyHostHandle h2 = PyHostHandle.builder("oculix.Item2")
                    .bindSession(rt.hostSession())
                    .prop("v", () -> PyInt.of(9))
                    .build();
            rt.bindHandle("item2", h2);
            rt.eval("assert item2.v == 9");

            rt.close();
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> h2.getAttr("v")).typeName());
        }
    }
}
