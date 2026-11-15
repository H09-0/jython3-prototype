package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class PyClass implements PyObject {
    private final String name;
    private final PyClass baseClass;
    private final Map<String, PyObject> members;
    private final List<PyClass> bases = new ArrayList<>();
    private final Map<String, PyObject> memberCache = new ConcurrentHashMap<>();
    private final Set<PyClass> subclasses = Collections.newSetFromMap(new WeakHashMap<>());
    private List<PyClass> mro;
    private volatile long version = 1;

    /**
     * Marks the root of the exception hierarchy. Every class that has this
     * class in its base list is an exception type; the flag is inherited, not
     * copied, so a custom {@code class E(ValueError)} is one automatically.
     */
    private boolean exceptionRoot;

    public PyClass(String name, PyClass baseClass, Map<String, PyObject> members) {
        this.name = name;
        this.baseClass = baseClass;
        this.members = members == null ? new HashMap<>() : new HashMap<>(members);
        if (baseClass != null) {
            this.bases.add(baseClass);
            baseClass.addSubclass(this);
        }
        this.mro = computeC3Mro();
    }

    public PyClass(String name, PyClass baseClass, Map<String, PyObject> members, List<PyClass> basesList) {
        this.name = name;
        this.baseClass = baseClass;
        this.members = members == null ? new HashMap<>() : new HashMap<>(members);
        if (basesList != null) {
            Set<PyClass> seenBases = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PyClass b : basesList) {
                if (!seenBases.add(b)) {
                    throw PyErrors.error("TypeError", "duplicate base class " + b.name());
                }
                this.bases.add(b);
                b.addSubclass(this);
            }
        } else if (baseClass != null) {
            this.bases.add(baseClass);
            baseClass.addSubclass(this);
        }
        this.mro = computeC3Mro();
    }

    public String name() { return name; }
    public PyClass baseClass() { return baseClass; }
    public Map<String, PyObject> members() { return Collections.unmodifiableMap(members); }
    public List<PyClass> getBases() { return bases; }
    public List<PyClass> mro() { return mro; }

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
    public synchronized void invalidateHierarchy() {
        this.version++;
        this.memberCache.clear();
        for (PyClass sub : subclasses) {
            sub.invalidateHierarchy();
        }
    }

    public synchronized void setMember(String attr, PyObject val) {
        members.put(attr, val);
        invalidateHierarchy();
    }

    public synchronized PyObject removeMember(String attr) {
        PyObject removed = members.remove(attr);
        invalidateHierarchy();
        return removed;
    }

    public void clearMemberCache() {
        invalidateHierarchy();
    }

    private List<PyClass> computeC3Mro() {
        if (bases.isEmpty()) {
            return List.of(this);
        }
        if (bases.size() == 1) {
            List<PyClass> baseMro = bases.get(0).mro();
            List<PyClass> result = new ArrayList<>(1 + baseMro.size());
            result.add(this);
            result.addAll(baseMro);
            return Collections.unmodifiableList(result);
        }
        List<List<PyClass>> seqs = new ArrayList<>(bases.size() + 1);
        for (PyClass b : bases) {
            seqs.add(new ArrayList<>(b.mro()));
        }
        seqs.add(new ArrayList<>(bases));

        List<PyClass> merged = new ArrayList<>();
        merged.add(this);
        while (true) {
            seqs.removeIf(List::isEmpty);
            if (seqs.isEmpty()) break;
            PyClass candidate = null;
            for (List<PyClass> seq : seqs) {
                PyClass head = seq.get(0);
                boolean inTail = false;
                for (List<PyClass> other : seqs) {
                    for (int i = 1; i < other.size(); i++) {
                        if (other.get(i) == head) {
                            inTail = true;
                            break;
                        }
                    }
                    if (inTail) break;
                }
                if (!inTail) {
                    candidate = head;
                    break;
                }
            }
            if (candidate == null) {
                StringBuilder baseNames = new StringBuilder();
                for (int i = 0; i < bases.size(); i++) {
                    if (i > 0) baseNames.append(", ");
                    baseNames.append(bases.get(i).name());
                }
                throw PyErrors.error("TypeError",
                        "Cannot create a consistent method resolution order (MRO) for bases " + baseNames);
            }
            merged.add(candidate);
            for (List<PyClass> seq : seqs) {
                if (!seq.isEmpty() && seq.get(0) == candidate) {
                    seq.remove(0);
                }
            }
        }
        return Collections.unmodifiableList(merged);
    }

    public PyObject lookupMember(String attr) {
        PyObject cached = memberCache.get(attr);
        if (cached != null) return cached;
        PyObject val = null;
        for (PyClass cls : mro) {
            val = cls.members.get(attr);
            if (val != null) break;
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
