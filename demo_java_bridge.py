# ════════════════════════════════════════════════════════════════════════════
# Jython 3 – Java Bridge Demo
# Demonstrates the full Java interoperability layer with blazing-fast
# cached reflection, bidirectional type conversion, and security controls.
# ════════════════════════════════════════════════════════════════════════════

print("=== Java Bridge Demo: Jython 3 ===")
print("")

# ─── 1. Static Method Calls ────────────────────────────────────────────────
print("--- 1. Static Methods (java.lang.Math) ---")
from java.lang import Math
print(Math.sqrt(144.0))
print(Math.pow(2.0, 8.0))
print(Math.max(100, 200))
print(Math.abs(-42))
print(Math.floor(3.9))

print("")

# ─── 2. Static Fields ──────────────────────────────────────────────────────
print("--- 2. Static Fields ---")
from java.lang import Integer
from java.lang import Double
print(Integer.MAX_VALUE)
print(Math.PI)

print("")

# ─── 3. Instantiate Java Objects ───────────────────────────────────────────
print("--- 3. Instantiate Java Objects ---")
from java.lang import StringBuilder
sb = StringBuilder()
sb.append("Jython")
sb.append(" 3")
sb.append(" is")
sb.append(" powerful!")
print(sb.toString())

print("")

# ─── 4. Java Collections: ArrayList ────────────────────────────────────────
print("--- 4. ArrayList ---")
from java.util import ArrayList
lst = ArrayList()
lst.add("alpha")
lst.add("beta")
lst.add("gamma")
print(lst.size())
print(lst.get(0))
print(lst[1])

print("")

# ─── 5. Iterate over Java Collection ───────────────────────────────────────
print("--- 5. Iterate Java ArrayList ---")
for item in lst:
    print(item)

print("")

# ─── 6. HashMap ────────────────────────────────────────────────────────────
print("--- 6. HashMap ---")
from java.util import HashMap
m = HashMap()
m.put("name", "Jython3")
m.put("version", "3.0")
m.put("type", "interpreter")
print(m.get("name"))
print(m["version"])
print(len(m))

print("")

# ─── 7. Exception Handling for Java Exceptions ─────────────────────────────
print("--- 7. Java Exception Handling ---")
from java.util import ArrayList
safe = ArrayList()
try:
    x = safe.get(99)
except Exception as e:
    print("Caught Java exception")

print("")

# ─── 8. String Operations ──────────────────────────────────────────────────
print("--- 8. Java String ---")
from java.lang import String
s = String("Hello, World!")
print(s.toLowerCase())
print(s.toUpperCase())
print(s.length())
print(s.contains("World"))

print("")

# ─── 9. Import with alias ──────────────────────────────────────────────────
print("--- 9. Import with Alias ---")
import java.util.ArrayList as AL
import java.util.HashMap as HM
al = AL()
al.add(10)
al.add(20)
al.add(30)
print(al.size())

print("")

# ─── 10. Performance: 1000 calls, cached reflection ────────────────────────
print("--- 10. Performance Test (1000 calls) ---")
from java.lang import Math
i = 0
total = 0
while i < 1000:
    total = total + Math.abs(i - 500)
    i = i + 1
print(total)

print("")
print("=== Java Bridge Demo Complete ===")
