package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class PyClass implements PyObject {
    private final String name;
    private final PyClass baseClass;
    private final Map<String, PyObject> members;
    private final java.util.List<PyClass> bases = new java.util.ArrayList<>();
    private final Map<String, PyObject> memberCache = new HashMap<>();
    private final Set<PyClass> subclasses = Collections.newSetFromMap(new WeakHashMap<>());
    private long version = 1;

    /**
     * Marks the root of the exception hierarchy. Every class that has this
     * class in its base list is an exception type; the flag is inherited, not
     * copied, so a custom {@code class E(ValueError)} is one automatically.
     */
    private boolean exceptionRoot;

    public PyClass(String name, PyClass baseClass, Map<String, PyObject> members) {
        this.name = name;
        this.baseClass = baseClass;
        this.members = members == null ? new HashMap<>() : members;
        if (baseClass != null) {
            this.bases.add(baseClass);
            baseClass.addSubclass(this);
        }
    }

    public PyClass(String name, PyClass baseClass, Map<String, PyObject> members, java.util.List<PyClass> basesList) {
        this.name = name;
        this.baseClass = baseClass;
        this.members = members == null ? new HashMap<>() : members;
        if (basesList != null) {
            this.bases.addAll(basesList);
            for (PyClass b : basesList) {
                b.addSubclass(this);
            }
        } else if (baseClass != null) {
            this.bases.add(baseClass);
            baseClass.addSubclass(this);
        }
    }

    public String name() { return name; }
    public PyClass baseClass() { return baseClass; }
    public Map<String, PyObject> members() { return members; }
    public java.util.List<PyClass> getBases() { return bases; }

    public long version() {
        return version;
    }

    public void addSubclass(PyClass sub) {
        if (sub != null) {
            subclasses.add(sub);
        }
    }

    /**
     * Invalidates member caches and increments the class version counter for this
     * class and all derived subclasses across the inheritance tree.
     */
    public void invalidateHierarchy() {
        this.version++;
        this.memberCache.clear();
        for (PyClass sub : subclasses) {
            sub.invalidateHierarchy();
        }
    }

    public void setMember(String attr, PyObject val) {
        members.put(attr, val);
        invalidateHierarchy();
    }

    public PyObject removeMember(String attr) {
        PyObject removed = members.remove(attr);
        invalidateHierarchy();
        return removed;
    }

    public void clearMemberCache() {
        invalidateHierarchy();
    }

    public PyObject lookupMember(String attr) {
        PyObject cached = memberCache.get(attr);
        if (cached != null) return cached;
        PyObject val = members.get(attr);
        if (val == null) {
            for (PyClass b : bases) {
                PyObject v = b.lookupMember(attr);
                if (v != null) {
                    val = v;
                    break;
                }
            }
        }
        if (val != null) {
            memberCache.put(attr, val);
        }
        return val;
    }

    public boolean hasMember(String attr) {
        return lookupMember(attr) != null;
    }

    /** Marks this class as the root of the exception hierarchy. */
    public void markExceptionRoot() {
        this.exceptionRoot = true;
    }

    /**
     * Whether this class may be used as an exception type. This follows the
     * real base list, so user subclasses of a built-in exception are exception
     * types without any extra registration.
     */
    public boolean isExceptionType() {
        if (exceptionRoot) return true;
        for (PyClass base : bases) {
            if (base.isExceptionType()) return true;
        }
        return false;
    }

    /**
     * Whether this class is {@code ancestor} or derives from it. Traversal uses
     * class identity over the real base list, so unrelated classes with the
     * same name do not match.
     */
    public boolean isSubclassOf(PyClass ancestor) {
        if (ancestor == null) return false;
        if (this == ancestor || this.baseClass == ancestor) return true;
        Set<PyClass> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<PyClass> pending = new ArrayDeque<>();
        pending.add(this);
        while (!pending.isEmpty()) {
            PyClass current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current == ancestor) return true;
            pending.addAll(current.bases);
        }
        return false;
    }

    @Override public String toString() {
        return "<class '" + name + "'>";
    }
}
