package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInstance;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PyTuple;
import org.example.jython3.security.ExecutionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/**
 * Standard {@code re} module backed by {@code java.util.regex}, providing
 * {@code search}, {@code match}, {@code fullmatch}, {@code findall}, {@code sub},
 * {@code split}, {@code compile}, {@code escape}, flags, and ReDoS timeout protection.
 */
public final class ReModule {
    private ReModule() {}

    private static final PyClass MATCH_CLASS = new PyClass("re.Match", null, Map.of());
    private static final PyClass PATTERN_CLASS = new PyClass("re.Pattern", null, Map.of());

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("error", ExceptionTypeRegistry.current().type("PatternError"));
        attrs.put("IGNORECASE", PyInt.of(Pattern.CASE_INSENSITIVE));
        attrs.put("I", PyInt.of(Pattern.CASE_INSENSITIVE));
        attrs.put("MULTILINE", PyInt.of(Pattern.MULTILINE));
        attrs.put("M", PyInt.of(Pattern.MULTILINE));
        attrs.put("DOTALL", PyInt.of(Pattern.DOTALL));
        attrs.put("S", PyInt.of(Pattern.DOTALL));
        attrs.put("UNICODE", PyInt.of(Pattern.UNICODE_CHARACTER_CLASS));
        attrs.put("U", PyInt.of(Pattern.UNICODE_CHARACTER_CLASS));

        attrs.put("compile", fn("compile", args -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "compile() takes 1 or 2 arguments");
            }
            int flags = args.length == 2 ? PyOps.toInt(args[1]) : 0;
            Pattern p = compilePattern(args[0], flags);
            return wrapPattern(p);
        }));

        attrs.put("search", fn("search", args -> {
            if (args.length < 2 || args.length > 3) {
                throw PyErrors.error("TypeError", "search() takes 2 or 3 arguments");
            }
            int flags = args.length == 3 ? PyOps.toInt(args[2]) : 0;
            Pattern p = compilePattern(args[0], flags);
            String text = requireStr(args[1], "search");
            Matcher m = p.matcher(interruptible(text));
            return m.find() ? wrapMatch(m, text) : PyNull.INSTANCE;
        }));

        attrs.put("match", fn("match", args -> {
            if (args.length < 2 || args.length > 3) {
                throw PyErrors.error("TypeError", "match() takes 2 or 3 arguments");
            }
            int flags = args.length == 3 ? PyOps.toInt(args[2]) : 0;
            Pattern p = compilePattern(args[0], flags);
            String text = requireStr(args[1], "match");
            Matcher m = p.matcher(interruptible(text));
            return m.lookingAt() ? wrapMatch(m, text) : PyNull.INSTANCE;
        }));

        attrs.put("fullmatch", fn("fullmatch", args -> {
            if (args.length < 2 || args.length > 3) {
                throw PyErrors.error("TypeError", "fullmatch() takes 2 or 3 arguments");
            }
            int flags = args.length == 3 ? PyOps.toInt(args[2]) : 0;
            Pattern p = compilePattern(args[0], flags);
            String text = requireStr(args[1], "fullmatch");
            Matcher m = p.matcher(interruptible(text));
            return m.matches() ? wrapMatch(m, text) : PyNull.INSTANCE;
        }));

        attrs.put("findall", fn("findall", args -> {
            if (args.length < 2 || args.length > 3) {
                throw PyErrors.error("TypeError", "findall() takes 2 or 3 arguments");
            }
            int flags = args.length == 3 ? PyOps.toInt(args[2]) : 0;
            Pattern p = compilePattern(args[0], flags);
            String text = requireStr(args[1], "findall");
            return doFindAll(p, text);
        }));

        attrs.put("sub", fn("sub", args -> {
            if (args.length < 3 || args.length > 5) {
                throw PyErrors.error("TypeError", "sub() takes 3 to 5 arguments");
            }
            int count = args.length >= 4 ? PyOps.toInt(args[3]) : 0;
            int flags = args.length == 5 ? PyOps.toInt(args[4]) : 0;
            Pattern p = compilePattern(args[0], flags);
            String repl = requireStr(args[1], "sub");
            String text = requireStr(args[2], "sub");
            return new PyStr(doSub(p, repl, text, count));
        }));

        attrs.put("split", fn("split", args -> {
            if (args.length < 2 || args.length > 4) {
                throw PyErrors.error("TypeError", "split() takes 2 to 4 arguments");
            }
            int maxSplit = args.length >= 3 ? PyOps.toInt(args[2]) : 0;
            int flags = args.length == 4 ? PyOps.toInt(args[3]) : 0;
            Pattern p = compilePattern(args[0], flags);
            String text = requireStr(args[1], "split");
            return doSplit(p, text, maxSplit);
        }));

        attrs.put("escape", fn("escape", args -> {
            PyOps.checkArgCount("escape", args, 1);
            return new PyStr(Pattern.quote(requireStr(args[0], "escape")));
        }));

        return new PyModule("re", attrs);
    }

    private static Pattern compilePattern(PyObject patObj, int flags) {
        if (!(patObj instanceof PyStr patStr)) {
            throw PyErrors.error("TypeError", "first argument must be string, not '" + PyOps.typeName(patObj) + "'");
        }
        try {
            return Pattern.compile(patStr.value(), flags);
        } catch (PatternSyntaxException pse) {
            throw PyErrors.error("PatternError", pse.getDescription());
        }
    }

    private static String requireStr(PyObject obj, String fn) {
        if (!(obj instanceof PyStr s)) {
            throw PyErrors.error("TypeError", "re." + fn + "() expected string, not '" + PyOps.typeName(obj) + "'");
        }
        return s.value();
    }

    private static PyObject doFindAll(Pattern p, String text) {
        Matcher m = p.matcher(interruptible(text));
        var list = new ArrayList<PyObject>();
        int groups = m.groupCount();
        while (m.find()) {
            if (groups == 0) {
                list.add(new PyStr(m.group()));
            } else if (groups == 1) {
                String g = m.group(1);
                list.add(new PyStr(g != null ? g : ""));
            } else {
                var tupleItems = new ArrayList<PyObject>(groups);
                for (int i = 1; i <= groups; i++) {
                    String g = m.group(i);
                    tupleItems.add(new PyStr(g != null ? g : ""));
                }
                list.add(new PyTuple(tupleItems));
            }
        }
        return new PyList(list);
    }

    private static String doSub(Pattern p, String repl, String text, int count) {
        Matcher m = p.matcher(interruptible(text));
        if (count <= 0) {
            return m.replaceAll(Matcher.quoteReplacement(repl));
        }
        var sb = new StringBuilder();
        int n = 0;
        while (n < count && m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            n++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static PyList doSplit(Pattern p, String text, int maxSplit) {
        Matcher m = p.matcher(interruptible(text));
        var out = new ArrayList<PyObject>();
        int last = 0;
        int splits = 0;
        while ((maxSplit <= 0 || splits < maxSplit) && m.find()) {
            out.add(new PyStr(text.substring(last, m.start())));
            for (int g = 1; g <= m.groupCount(); g++) {
                String gv = m.group(g);
                out.add(gv != null ? new PyStr(gv) : PyNull.INSTANCE);
            }
            last = m.end();
            splits++;
        }
        out.add(new PyStr(text.substring(last)));
        return new PyList(out);
    }

    private static PyInstance wrapPattern(Pattern p) {
        PyInstance inst = new PyInstance(PATTERN_CLASS);
        inst.setField("pattern", new PyStr(p.pattern()));
        inst.setField("flags", PyInt.of(p.flags()));
        inst.setField("search", new PyBuiltinFunction("Pattern.search", args -> {
            PyOps.checkArgCount("search", args, 1);
            String text = requireStr(args[0], "search");
            Matcher m = p.matcher(interruptible(text));
            return m.find() ? wrapMatch(m, text) : PyNull.INSTANCE;
        }));
        inst.setField("match", new PyBuiltinFunction("Pattern.match", args -> {
            PyOps.checkArgCount("match", args, 1);
            String text = requireStr(args[0], "match");
            Matcher m = p.matcher(interruptible(text));
            return m.lookingAt() ? wrapMatch(m, text) : PyNull.INSTANCE;
        }));
        inst.setField("fullmatch", new PyBuiltinFunction("Pattern.fullmatch", args -> {
            PyOps.checkArgCount("fullmatch", args, 1);
            String text = requireStr(args[0], "fullmatch");
            Matcher m = p.matcher(interruptible(text));
            return m.matches() ? wrapMatch(m, text) : PyNull.INSTANCE;
        }));
        inst.setField("findall", new PyBuiltinFunction("Pattern.findall", args -> {
            PyOps.checkArgCount("findall", args, 1);
            return doFindAll(p, requireStr(args[0], "findall"));
        }));
        inst.setField("sub", new PyBuiltinFunction("Pattern.sub", args -> {
            if (args.length < 2 || args.length > 3) throw PyErrors.error("TypeError", "sub() takes 2 or 3 arguments");
            int count = args.length == 3 ? PyOps.toInt(args[2]) : 0;
            return new PyStr(doSub(p, requireStr(args[0], "sub"), requireStr(args[1], "sub"), count));
        }));
        inst.setField("split", new PyBuiltinFunction("Pattern.split", args -> {
            if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", "split() takes 1 or 2 arguments");
            int maxSplit = args.length == 2 ? PyOps.toInt(args[1]) : 0;
            return doSplit(p, requireStr(args[0], "split"), maxSplit);
        }));
        return inst;
    }

    private static PyInstance wrapMatch(Matcher m, String text) {
        int groupCount = m.groupCount();
        String[] captured = new String[groupCount + 1];
        int[] starts = new int[groupCount + 1];
        int[] ends = new int[groupCount + 1];
        for (int i = 0; i <= groupCount; i++) {
            captured[i] = m.group(i);
            starts[i] = m.start(i);
            ends[i] = m.end(i);
        }
        PyInstance inst = new PyInstance(MATCH_CLASS);
        inst.setField("string", new PyStr(text));
        inst.setField("group", new PyBuiltinFunction("Match.group", args -> {
            if (args.length == 0) return captured[0] != null ? new PyStr(captured[0]) : PyNull.INSTANCE;
            if (args.length == 1) {
                int idx = PyOps.toInt(args[0]);
                if (idx < 0 || idx > groupCount) throw PyErrors.error("IndexError", "no such group");
                return captured[idx] != null ? new PyStr(captured[idx]) : PyNull.INSTANCE;
            }
            var items = new ArrayList<PyObject>(args.length);
            for (PyObject a : args) {
                int idx = PyOps.toInt(a);
                if (idx < 0 || idx > groupCount) throw PyErrors.error("IndexError", "no such group");
                items.add(captured[idx] != null ? new PyStr(captured[idx]) : PyNull.INSTANCE);
            }
            return new PyTuple(items);
        }));
        inst.setField("groups", new PyBuiltinFunction("Match.groups", args -> {
            PyObject defVal = args.length >= 1 ? args[0] : PyNull.INSTANCE;
            var items = new ArrayList<PyObject>(groupCount);
            for (int i = 1; i <= groupCount; i++) {
                items.add(captured[i] != null ? new PyStr(captured[i]) : defVal);
            }
            return new PyTuple(items);
        }));
        inst.setField("start", new PyBuiltinFunction("Match.start", args -> {
            int idx = args.length >= 1 ? PyOps.toInt(args[0]) : 0;
            if (idx < 0 || idx > groupCount) throw PyErrors.error("IndexError", "no such group");
            return PyInt.of(starts[idx]);
        }));
        inst.setField("end", new PyBuiltinFunction("Match.end", args -> {
            int idx = args.length >= 1 ? PyOps.toInt(args[0]) : 0;
            if (idx < 0 || idx > groupCount) throw PyErrors.error("IndexError", "no such group");
            return PyInt.of(ends[idx]);
        }));
        inst.setField("span", new PyBuiltinFunction("Match.span", args -> {
            int idx = args.length >= 1 ? PyOps.toInt(args[0]) : 0;
            if (idx < 0 || idx > groupCount) throw PyErrors.error("IndexError", "no such group");
            return new PyTuple(List.of(PyInt.of(starts[idx]), PyInt.of(ends[idx])));
        }));
        return inst;
    }

    private static CharSequence interruptible(String s) {
        ExecutionContext ctx = ExecutionContext.current();
        if (ctx == null) return s;
        return new CharSequence() {
            private int ops = 0;

            private void tick() {
                if ((++ops & 0xF) == 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw PyErrors.error("KeyboardInterrupt", "Regex interrupted");
                    }
                    ctx.stepInstruction();
                }
            }

            @Override public int length() { return s.length(); }
            @Override public char charAt(int index) { tick(); return s.charAt(index); }
            @Override public CharSequence subSequence(int start, int end) {
                tick();
                return s.subSequence(start, end);
            }
            @Override public String toString() { return s; }
        };
    }
}
