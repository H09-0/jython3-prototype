package org.example.jython3.field;

import org.example.jython3.Jython3Interpreter;

/** Project 4: Java Bridge Automation App (ArrayList, HashMap, StringBuilder chaining, Math). */
public final class JavaBridgeScenario implements FieldScenario {

    private static final String CODE = """
        from java.util import ArrayList, HashMap
        from java.lang import StringBuilder, Math

        cache = HashMap()
        items = ArrayList()
        sb = StringBuilder()

        for i in range(500):
            key = "k_" + str(i)
            val = Math.sqrt(float(i * i + 1))
            cache.put(key, val)
            items.add(key)
            if i < 5:
                sb.append(key).append(";")

        count = 0
        for item in items:
            if cache[item] >= 0.0:
                count += 1
        summary = sb.toString()
        """;

    @Override public String name() { return "Project 4: Java Bridge Automation App"; }
    @Override public String pythonCode() { return CODE; }

    @Override
    public void verify(Jython3Interpreter interp) {
        FieldScenario.assertTrue("count == 500", "500".equals(interp.getGlobal("count").toString()));
        FieldScenario.assertTrue("summary prefix", interp.getGlobal("summary").toString().startsWith("k_0;k_1;"));
    }
}
