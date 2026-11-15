package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyStr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/** Standard {@code re} module backed by {@code java.util.regex}, raising {@code re.error} on invalid patterns. */
public final class ReModule {
    private ReModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("error", ExceptionTypeRegistry.current().type("PatternError"));

        attrs.put("search", fn("search", args -> {
            if (args.length < 2) {
                throw PyErrors.error("TypeError", "search() missing required arguments (pattern, string)");
            }
            if (!(args[0] instanceof PyStr patStr) || !(args[1] instanceof PyStr textStr)) {
                throw PyErrors.error("TypeError", "re.search() arguments must be str, not '"
                        + PyOps.typeName(args[0]) + "' and '" + PyOps.typeName(args[1]) + "'");
            }
            try {
                Pattern p = Pattern.compile(patStr.value());
                Matcher m = p.matcher(textStr.value());
                return m.find() ? new PyStr(m.group()) : new PyNull();
            } catch (PatternSyntaxException pse) {
                throw PyErrors.error("PatternError", pse.getDescription());
            }
        }));

        attrs.put("findall", fn("findall", args -> {
            if (args.length < 2) {
                throw PyErrors.error("TypeError", "findall() missing required arguments (pattern, string)");
            }
            if (!(args[0] instanceof PyStr patStr) || !(args[1] instanceof PyStr textStr)) {
                throw PyErrors.error("TypeError", "re.findall() arguments must be str, not '"
                        + PyOps.typeName(args[0]) + "' and '" + PyOps.typeName(args[1]) + "'");
            }
            try {
                Pattern p = Pattern.compile(patStr.value());
                Matcher m = p.matcher(textStr.value());
                var list = new ArrayList<PyObject>();
                while (m.find()) list.add(new PyStr(m.group()));
                return new PyList(list);
            } catch (PatternSyntaxException pse) {
                throw PyErrors.error("PatternError", pse.getDescription());
            }
        }));

        return new PyModule("re", attrs);
    }
}
