# ==========================================
# Jython 3 Comprehensive Demo Script
# Demonstrates Python 3 & OOP in action
# ==========================================

print("=== 1. Basic Types & Arithmetic ===")
a = 15
b = 4
print("Addition:", a + b)
print("Floor division:", a // b)
print("Modulo:", a % b)
print("Power:", 2 ** 8)
print("Float:", 3.14 * 2)

print("\n=== 2. Booleans & Indented Conditionals ===")
flag = True
print("Flag:", flag)
if a > 20:
    print("Greater than 20")
elif a == 15:
    print("Condition matched: a is 15 (elif in indented block!)")
else:
    print("Less than 15")

print("\n=== 3. Data Structures: Lists & Dictionaries ===")
items = [
    3,
    1,
    4,
    1,
    5,
    9
]
items.append(2)
items.sort()
print("Sorted list (from multi-line brackets):", items)

config = {'app': 'Oculix', 'version': 3, 'active': True}
config['engine'] = 'Jython3'
print("App name:", config.get('app'))
print("Engine:", config['engine'])

print("\n=== 4. Multi-line Indented Functions ===")
def compute_stats(numbers):
    total = 0
    count = 0
    for n in numbers:
        total += n
        count += 1
    return total // count

sample = [10, 20, 30, 40]
print("Average of", sample, "=", compute_stats(sample))

print("\n=== 5. Object-Oriented Programming (Classes & Inheritance) ===")
class Service:
    def __init__(self, name, port):
        self.name = name
        self.port = port
        self.running = False

    def start(self):
        self.running = True
        return self.name + " started on port " + str(self.port)

class DatabaseService(Service):
    def __init__(self, name, port, db_type):
        self.name = name
        self.port = port
        self.db_type = db_type
        self.running = False

    def get_info(self):
        return self.name + " (" + self.db_type + ") on port " + str(self.port)

db = DatabaseService("OculixDB", 5432, "PostgreSQL")
print(db.start())
print("Service info:", db.get_info())
print("Is DatabaseService?", isinstance(db, DatabaseService))
print("Is Service?", isinstance(db, Service))

print("\n=== 6. Builtins & String Methods ===")
msg = "  jython 3 is ready!  "
cleaned = msg.strip().upper()
print("Cleaned message:", cleaned)
print("Length:", len(cleaned))
print("Sum of range(1, 6):", sum([1, 2, 3, 4, 5]))

print("\n=== 7. Exception Handling ===")
try:
    bad = 10 / 0
except ZeroDivisionError:
    print("ZeroDivisionError successfully caught and handled!")

print("\nAll Jython 3 features executed successfully!")
