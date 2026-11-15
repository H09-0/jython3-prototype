package org.example.jython3.runtime;

import java.util.HashMap;
import java.util.Map;


public final class PyInstance implements PyObject {
    private final PyClass pyClass;
    private final Map<String, PyObject> fields = new HashMap<>();
    private Map<String, PyBoundMethod> boundMethodCache;
    private long lastClassVersion = -1;

    public PyInstance(PyClass pyClass) {
        this.pyClass = pyClass;
    }

    public PyClass pyClass() {
        return pyClass;
    }

    public boolean isInstanceOf(PyClass target) {
        return pyClass.isSubclassOf(target);
    }

    public PyObject getField(String name) {
        if (!pyClass.hasProperties()) {
            PyObject val = fields.get(name);
            if (val != null) return val;
        }
        PyObject member = pyClass.lookupMember(name);
        if (member instanceof PyProperty prop && prop.isDataDescriptor()) {
            return invokePropertyGetter(prop, name);
        }
        PyObject val = fields.get(name);
        if (val != null) {
            return val;
        }
        if (member instanceof PyProperty prop) {
            return invokePropertyGetter(prop, name);
        }
        if (member instanceof PyStaticMethod sm) {
            return sm.callable();
        }
        if (member instanceof PyClassMethod cm) {
            return cm.bind(pyClass);
        }
        if (member instanceof PyFunction fn) {
            long currentVer = pyClass.version();
            if (boundMethodCache == null) {
                boundMethodCache = new HashMap<>(4);
                lastClassVersion = currentVer;
            } else if (lastClassVersion != currentVer) {
                boundMethodCache.clear();
                lastClassVersion = currentVer;
            }
            PyBoundMethod cached = boundMethodCache.get(name);
            if (cached != null) return cached;
            PyBoundMethod bm = new PyBoundMethod(this, fn);
            boundMethodCache.put(name, bm);
            return bm;
        }
        if (member instanceof PyBuiltinFunction bf) {
            return new PyBuiltinFunction(bf.name(), (a, kw) -> {
                PyObject[] bound = new PyObject[a.length + 1];
                bound[0] = this;
                System.arraycopy(a, 0, bound, 1, a.length);
                return bf.callWithKw(bound, kw);
            });
        }
        if (member != null) {
            return member;
        }
        if (fields.containsKey(name)) {
            return fields.get(name);
        }
        if ("__class__".equals(name)) {
            return pyClass;
        }
        throw PyErrors.error("AttributeError", "'" + pyClass.name() + "' object has no attribute '" + name + "'");
    }

    private PyObject invokePropertyGetter(PyProperty prop, String name) {
        if (prop.fget() == null) {
            throw PyErrors.error("AttributeError",
                    "property '" + name + "' of '" + pyClass.name() + "' object has no getter");
        }
        PyCaller caller = PyCaller.current();
        if (caller == null) {
            throw PyErrors.error("RuntimeError", "no active interpreter for property");
        }
        return caller.call(prop.fget(), new PyObject[]{this});
    }

    public void setField(String name, PyObject val) {
        if (pyClass.hasProperties()) {
            PyObject member = pyClass.lookupMember(name);
            if (member instanceof PyProperty prop) {
                if (prop.fset() == null) {
                    throw PyErrors.error("AttributeError",
                            "property '" + name + "' of '" + pyClass.name() + "' object has no setter");
                }
                PyCaller caller = PyCaller.current();
                if (caller == null) {
                    throw PyErrors.error("RuntimeError", "no active interpreter for property");
                }
                caller.call(prop.fset(), new PyObject[]{this, val});
                return;
            }
        }
        fields.put(name, val);
        if (boundMethodCache != null) {
            boundMethodCache.remove(name);
        }
    }

    public boolean deleteField(String name) {
        if (pyClass.hasProperties()) {
            PyObject member = pyClass.lookupMember(name);
            if (member instanceof PyProperty prop) {
                if (prop.fdel() == null) {
                    throw PyErrors.error("AttributeError",
                            "property '" + name + "' of '" + pyClass.name() + "' object has no deleter");
                }
                PyCaller caller = PyCaller.current();
                if (caller == null) {
                    throw PyErrors.error("RuntimeError", "no active interpreter for property");
                }
                caller.call(prop.fdel(), new PyObject[]{this});
                return true;
            }
        }
        if (!fields.containsKey(name)) return false;
        fields.remove(name);
        if (boundMethodCache != null) {
            boundMethodCache.remove(name);
        }
        return true;
    }

    public Map<String, PyObject> fields() {
        return fields;
    }

    public boolean hasField(String name) {
        return fields.containsKey(name) || pyClass.hasMember(name) || "__class__".equals(name);
    }

    @Override
    public String toString() {
        PyCaller interp = PyCaller.current();
        if (interp != null) {
            PyObject strMethod = pyClass.lookupMember("__str__");
            if (strMethod instanceof PyFunction fn) {
                return interp.call(fn, new PyObject[]{this}).toString();
            }
            if (strMethod instanceof PyBoundMethod bm) {
                return interp.call(bm.fn(), new PyObject[]{this}).toString();
            }

            PyObject reprMethod = pyClass.lookupMember("__repr__");
            if (reprMethod instanceof PyFunction fn) {
                return interp.call(fn, new PyObject[]{this}).toString();
            }
            if (reprMethod instanceof PyBoundMethod bm) {
                return interp.call(bm.fn(), new PyObject[]{this}).toString();
            }
        }
        return "<" + pyClass.name() + " instance>";
    }
}

