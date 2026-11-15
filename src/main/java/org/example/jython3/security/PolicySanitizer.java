package org.example.jython3.security;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFunction;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInstance;
import org.example.jython3.runtime.PyJavaClass;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ensures any host-injected {@link PyObject} or wrapper adheres to an interpreter's
 * {@link JavaAccessPolicy}. Handles circular references safely via {@link IdentityHashMap}.
 */
public final class PolicySanitizer {
    private PolicySanitizer() {}

    public static PyObject sanitize(PyObject val, JavaAccessPolicy policy) {
        return sanitize(val, policy, null, new IdentityHashMap<>());
    }

    public static PyObject sanitize(PyObject val, JavaAccessPolicy policy, HostSession targetSession) {
        return sanitize(val, policy, targetSession, new IdentityHashMap<>());
    }

    private static PyObject sanitize(PyObject val, JavaAccessPolicy policy, HostSession targetSession,
                                     Map<PyObject, PyObject> visited) {
        if (val == null || val instanceof PyNull) return val;
        PyObject seen = visited.get(val);
        if (seen != null) return seen;

        if (val instanceof PyHostHandle hh) {
            if (hh.session() != null && hh.session() != targetSession) {
                throw PyErrors.error("SecurityError",
                        "host capability handle '" + hh.handleTypeName() + "' cannot cross interpreter boundaries");
            }
            return val;
        }

        if (val instanceof PyJavaClass jc) {
            if (!policy.canAccessClass(jc.clazz().getName())) {
                throw JavaBridge.denied(jc.clazz().getName());
            }
            PyJavaClass res = new PyJavaClass(jc.clazz(), policy);
            visited.put(val, res);
            return res;
        }
        if (val instanceof PyJavaObject jo) {
            if (!policy.canAccessClass(jo.object().getClass().getName())) {
                throw JavaBridge.denied(jo.object().getClass().getName());
            }
            PyJavaObject res = new PyJavaObject(jo.object(), policy);
            visited.put(val, res);
            return res;
        }
        if (val instanceof PyList list) {
            var sanitizedItems = new ArrayList<PyObject>();
            var res = new PyList(sanitizedItems);
            visited.put(val, res);
            for (PyObject item : list.items()) {
                sanitizedItems.add(sanitize(item, policy, targetSession, visited));
            }
            return res;
        }
        if (val instanceof PyDict dict) {
            var res = new PyDict();
            visited.put(val, res);
            for (var entry : dict.map().entrySet()) {
                res.put(sanitize(entry.getKey(), policy, targetSession, visited),
                        sanitize(entry.getValue(), policy, targetSession, visited));
            }
            return res;
        }
        if (val instanceof PyModule mod) {
            var sanitizedAttrs = new HashMap<String, PyObject>();
            var res = new PyModule(mod.name(), sanitizedAttrs);
            visited.put(val, res);
            for (var entry : mod.attrs().entrySet()) {
                sanitizedAttrs.put(entry.getKey(), sanitize(entry.getValue(), policy, targetSession, visited));
            }
            return res;
        }
        if (val instanceof PyGenerator gen) {
            visited.put(val, gen);
            sanitize(gen.fn(), policy, targetSession, visited);
            var sanitizedLocals = new HashMap<String, PyObject>();
            for (var entry : gen.localsSnapshot().entrySet()) {
                sanitizedLocals.put(entry.getKey(), sanitize(entry.getValue(), policy, targetSession, visited));
            }
            gen.replaceLocals(sanitizedLocals);
            return gen;
        }
        if (val instanceof PyFunction fn) {
            var res = new PyFunction(fn.name(), fn.params(), fn.defaults(),
                    fn.argsParam(), fn.kwargsParam(), fn.code(), new HashMap<>());
            visited.put(val, res);
            for (var entry : fn.closure().entrySet()) {
                res.closure().put(entry.getKey(), sanitize(entry.getValue(), policy, targetSession, visited));
            }
            return res;
        }
        if (val instanceof PyClass cls) {
            var sanitizedMembers = new HashMap<String, PyObject>();
            var sanitizedBases = new ArrayList<PyClass>();
            PyClass sanitizedBase = cls.baseClass() != null
                    ? (PyClass) sanitize(cls.baseClass(), policy, targetSession, visited)
                    : null;
            var res = new PyClass(cls.name(), sanitizedBase, sanitizedMembers, sanitizedBases);
            visited.put(val, res);
            for (PyClass b : cls.getBases()) {
                sanitizedBases.add((PyClass) sanitize(b, policy, targetSession, visited));
            }
            for (var entry : cls.members().entrySet()) {
                res.setMember(entry.getKey(), sanitize(entry.getValue(), policy, targetSession, visited));
            }
            return res;
        }
        if (val instanceof PyInstance inst) {
            PyClass sanitizedCls = (PyClass) sanitize(inst.pyClass(), policy, targetSession, visited);
            var res = new PyInstance(sanitizedCls);
            visited.put(val, res);
            for (var entry : inst.fields().entrySet()) {
                res.setField(entry.getKey(), sanitize(entry.getValue(), policy, targetSession, visited));
            }
            return res;
        }
        return val;
    }
}
