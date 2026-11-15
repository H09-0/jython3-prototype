package org.example.jython3.runtime;

/**
 * Built-in {@code @property} descriptor supporting {@code fget}, {@code fset},
 * {@code fdel}, and {@code .getter(fn)}, {@code .setter(fn)}, {@code .deleter(fn)}.
 */
public final class PyProperty implements PyObject {
    private final PyObject fget;
    private final PyObject fset;
    private final PyObject fdel;
    private final String doc;

    public PyProperty(PyObject fget, PyObject fset, PyObject fdel, String doc) {
        this.fget = fget;
        this.fset = fset;
        this.fdel = fdel;
        this.doc = doc;
    }

    public PyObject fget() { return fget; }
    public PyObject fset() { return fset; }
    public PyObject fdel() { return fdel; }
    public String doc() { return doc; }

    public boolean isDataDescriptor() {
        return fset != null || fdel != null;
    }

    public PyObject getAttr(String attr) {
        return switch (attr) {
            case "fget" -> fget != null ? fget : PyNull.INSTANCE;
            case "fset" -> fset != null ? fset : PyNull.INSTANCE;
            case "fdel" -> fdel != null ? fdel : PyNull.INSTANCE;
            case "__doc__" -> doc != null ? new PyStr(doc) : PyNull.INSTANCE;
            case "getter" -> new PyBuiltinFunction("property.getter", args -> {
                PyOps.checkArgCount("getter", args, 1);
                return new PyProperty(args[0], fset, fdel, doc);
            });
            case "setter" -> new PyBuiltinFunction("property.setter", args -> {
                PyOps.checkArgCount("setter", args, 1);
                return new PyProperty(fget, args[0], fdel, doc);
            });
            case "deleter" -> new PyBuiltinFunction("property.deleter", args -> {
                PyOps.checkArgCount("deleter", args, 1);
                return new PyProperty(fget, fset, args[0], doc);
            });
            default -> throw PyErrors.error("AttributeError", "'property' object has no attribute '" + attr + "'");
        };
    }

    @Override
    public String toString() {
        return "<property object>";
    }
}
