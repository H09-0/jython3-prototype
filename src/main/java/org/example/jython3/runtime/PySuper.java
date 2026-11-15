package org.example.jython3.runtime;

import java.util.List;

public final class PySuper implements PyObject {
    private final PyClass cls;
    private final PyInstance inst;

    public PySuper(PyClass cls, PyInstance inst) {
        this.cls = cls;
        this.inst = inst;
    }

    public PyInstance inst() { return inst; }
    public PyClass cls() { return cls; }

    public PyObject getMethod(String name) {
        if (cls != null && inst != null) {
            List<PyClass> mro = inst.pyClass().mro();
            int startIdx = -1;
            for (int i = 0; i < mro.size(); i++) {
                if (mro.get(i) == cls) {
                    startIdx = i + 1;
                    break;
                }
            }
            if (startIdx >= 0) {
                for (int i = startIdx; i < mro.size(); i++) {
                    PyObject m = mro.get(i).members().get(name);
                    if (m != null) {
                        if (m instanceof PyFunction fn) return new PyBoundMethod(inst, fn);
                        if (m instanceof PyStaticMethod sm) return sm.callable();
                        if (m instanceof PyClassMethod cm) return cm.bind(inst.pyClass());
                        if (m instanceof PyProperty prop) {
                            if (prop.fget() == null) {
                                throw PyErrors.error("AttributeError", "property '" + name + "' has no getter");
                            }
                            PyCaller caller = PyCaller.current();
                            if (caller == null) throw PyErrors.error("RuntimeError", "no active interpreter for property");
                            return caller.call(prop.fget(), new PyObject[]{inst});
                        }
                        return m;
                    }
                }
            }
        }
        throw PyErrors.error("AttributeError", "'super' object has no attribute '" + name + "'");
    }

    @Override public String toString() { return "<super: ...>"; }
}
