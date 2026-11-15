# Pure-Python collections module for Jython3.

class Counter:
    def __init__(self, iterable=None):
        self.counts = {}
        if iterable is not None:
            self.update(iterable)

    def update(self, iterable):
        if isinstance(iterable, dict):
            for k in iterable:
                self.counts[k] = self.counts.get(k, 0) + iterable[k]
        else:
            for item in iterable:
                self.counts[item] = self.counts.get(item, 0) + 1

    def get(self, key, default=0):
        return self.counts.get(key, default)

    def total(self):
        return sum(self.counts.values())

    def keys(self):
        return self.counts.keys()

    def values(self):
        return self.counts.values()

    def items(self):
        return self.counts.items()

    def most_common(self, n=None):
        pairs = [(k, self.counts[k]) for k in self.counts]
        pairs = sorted(pairs, key=lambda p: -p[1])
        if n is not None:
            return pairs[:n]
        return pairs

    def elements(self):
        out = []
        for k in self.counts:
            c = self.counts[k]
            for _ in range(c):
                out.append(k)
        return out

    def __len__(self):
        return len(self.counts)


class defaultdict:
    def __init__(self, default_factory=None):
        self.default_factory = default_factory
        self._data = {}

    def get(self, key):
        if key in self._data:
            return self._data[key]
        if self.default_factory is None:
            raise KeyError(str(key))
        val = self.default_factory()
        self._data[key] = val
        return val

    def set(self, key, value):
        self._data[key] = value

    def keys(self):
        return self._data.keys()

    def values(self):
        return self._data.values()

    def items(self):
        return self._data.items()

    def to_dict(self):
        return self._data.copy()

    def __len__(self):
        return len(self._data)


class deque:
    def __init__(self, iterable=None, maxlen=None):
        self.maxlen = maxlen
        self._items = []
        if iterable is not None:
            self.extend(iterable)

    def append(self, x):
        self._items.append(x)
        if self.maxlen is not None and len(self._items) > self.maxlen:
            self._items.pop(0)

    def appendleft(self, x):
        self._items.insert(0, x)
        if self.maxlen is not None and len(self._items) > self.maxlen:
            self._items.pop()

    def pop(self):
        if len(self._items) == 0:
            raise IndexError("pop from an empty deque")
        return self._items.pop()

    def popleft(self):
        if len(self._items) == 0:
            raise IndexError("pop from an empty deque")
        return self._items.pop(0)

    def extend(self, iterable):
        for x in iterable:
            self.append(x)

    def clear(self):
        self._items.clear()

    def count(self, x):
        return self._items.count(x)

    def to_list(self):
        return list(self._items)

    def __len__(self):
        return len(self._items)
