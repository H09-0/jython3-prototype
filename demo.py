# ==========================================
# Jython 3 Comprehensive Demo Script
# Verifies Python 3 replacement foundations
# ==========================================

print("=== 1. Basic Types & Arithmetic ===")
a = 15
b = 4
print("Addition:", a + b)
print("Floor division:", a // b)
print("Modulo:", a % b)
print("Power:", 2 ** 8)
print("Float:", 3.14 * 2)

print("\n=== 2. Booleans & Comparisons ===")
flag = True
print("Flag:", flag)
print("Comparison:", (a > b) and (b < 10))
if a > 20:
    print("Greater than 20")
elif a == 15:
    print("Exactly 15 (elif works!)")
else:
    print("Less than 15")

print("\n=== 3. Data Structures: Lists & Dictionaries ===")
items = [3, 1, 4, 1, 5, 9]
items.append(2)
items.sort()
print("Sorted list:", items)

config = {'app': 'Oculix', 'version': 3, 'active': True}
config['engine'] = 'Jython3'
print("App name:", config.get('app'))
print("Engine:", config['engine'])

print("\n=== 4. Functions ===")
def square(x): return x * x
def greet(name): return "Hello, " + name

print("square(6) =", square(6))
print(greet("Oculix"))

print("\n=== 5. Builtins & String Methods ===")
msg = "  jython 3 is ready!  "
cleaned = msg.strip().upper()
print("Cleaned message:", cleaned)
print("Length:", len(cleaned))
print("Sum of range(1, 6):", sum([1, 2, 3, 4, 5]))

print("\n=== 6. Exception Handling ===")
try:
    bad = 10 / 0
except ZeroDivisionError:
    print("ZeroDivisionError successfully caught and handled!")

print("\nAll Jython 3 features executed successfully!")
