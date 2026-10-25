# Standard Library Documentation

This document describes all built-in modules provided natively by the `jython3-prototype` execution engine.

Each module is implemented in Java (`BuiltinModules.java`) for near-native performance and seamless JVM integration.

---

## 1. `math`
Provides mathematical functions and constants conforming to CPython's `math` module.

```python
import math

val = math.sqrt(144)      # 12.0
ang = math.sin(math.pi / 2) # 1.0
flr = math.floor(3.8)      # 3
```

- **Functions**: `sqrt(x)`, `sin(x)`, `cos(x)`, `tan(x)`, `log(x)`, `floor(x)`, `ceil(x)`, `abs(x)`, `pow(x, y)`
- **Constants**: `pi` (3.141592653589793), `e` (2.718281828459045), `inf` (Double.POSITIVE_INFINITY)

---

## 2. `sys`
Provides interpreter runtime variables and system configuration.

```python
import sys

print("Python version:", sys.version)    # '3.0.0 (Jython3-prototype)'
print("Platform:", sys.platform)          # 'java'
print("CLI Arguments:", sys.argv)         # ['script.py', 'arg1', ...]
```

- **Attributes**: `argv` (list of CLI arguments), `version`, `platform`, `maxsize` (2147483647)
- **Functions**: `exit(code=0)` (raises `SystemExit` with exit code)

---

## 3. `os`
Provides miscellaneous operating system interfaces.

```python
import os

cwd = os.getcwd()           # Current working directory string
pid = os.getpid()           # Current process ID integer
files = os.listdir(".")      # List of file and directory names
```

- **Attributes**: `sep` (system path separator), `pathsep` (system PATH delimiter), `name` ('java')
- **Functions**: `getcwd()`, `getpid()`, `environ()`, `listdir(path=".")`

---

## 4. `io`
Provides file I/O streams and open capabilities.

```python
with_file = open("test.txt", "w")
with_file.write("Hello from Jython 3!")
with_file.close()

read_file = open("test.txt", "r")
content = read_file.read()
read_file.close()
```

- **Functions**: `open(filename, mode='r')`
- **Supported Modes**: `'r'` (read), `'w'` (truncate and write), `'a'` (append)
- **File Object Methods**: `.read()`, `.write(data)`, `.close()`, `.name`, `.mode`

---

## 5. `json`
Provides JSON encoding and decoding.

```python
import json

data = {"name": "Alice", "scores": [95, 88, 92], "active": True}
json_str = json.dumps(data)
parsed = json.loads(json_str)

print(parsed["name"])  # 'Alice'
```

- **Functions**:
  - `dumps(obj)`: Serializes Python `dict`, `list`, `str`, `int`, `float`, `bool`, and `None` into JSON string.
  - `loads(json_str)`: Deserializes valid JSON strings into corresponding Python data structures.

---

## 6. `re`
Provides regular expression matching operations backed by Java's `java.util.regex` engine.

```python
import re

found = re.search(r"\d+", "Item 42 in order 105")
print(found)  # '42'

all_nums = re.findall(r"\d+", "Item 42 in order 105")
print(all_nums)  # ['42', '105']
```

- **Functions**:
  - `search(pattern, string)`: Returns the first matching substring or `None`.
  - `findall(pattern, string)`: Returns a list of all non-overlapping matches.

---

## 7. `time`
Provides time access, conversions, and execution suspension.

```python
import time

start_ns = time.time_ns()      # Exact Epoch nanoseconds (BigInt)
now_sec = time.time()          # Current Epoch seconds (float)
perf = time.perf_counter()     # High-resolution benchmarking counter
time.sleep(0.05)               # Suspend execution for 50 milliseconds
```

- **Functions**:
  - `time()`: Current time in seconds since the Epoch (January 1, 1970 UTC).
  - `time_ns()`: Exact integer timestamp in nanoseconds since the Epoch.
  - `sleep(seconds)`: Pauses execution. Validates strictly against negative values, `NaN`, and `Infinity`.
  - `monotonic()`: Monotonic clock for interval measurement.
  - `perf_counter()`: Benchmarking timer with highest available resolution.

---

## 8. `random`
Provides pseudorandom number generators. Each imported module instance maintains its own isolated PRNG instance.

```python
import random

val = random.random()                         # Float in [0.0, 1.0)
dice = random.randint(1, 6)                   # Integer in [1, 6]
huge = random.randint(10**25, 10**26)          # Arbitrary-precision integer!
winner = random.choice(["Alice", "Bob", "Eve"]) # Choice from list
emoji = random.choice("😀😃😄")               # Codepoint-safe choice from string
```

- **Functions**:
  - `random()`: Returns random float in `[0.0, 1.0)`.
  - `randint(a, b)`: Returns random integer in `[a, b]` using unbiased rejection sampling over arbitrary-precision integers.
  - `choice(sequence)`: Returns a random element from a non-empty sequence (Unicode-safe).
  - `seed(n=None)`: Seeds the local PRNG instance.
