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


class OrderedDict:
    def __init__(self, items=None):
        self._d = {}
        if items is not None:
            if isinstance(items, dict):
                for k in items:
                    self._d[k] = items[k]
            else:
                for k, v in items:
                    self._d[k] = v

    def __getitem__(self, k):
        return self._d[k]

    def __setitem__(self, k, v):
        self._d[k] = v

    def __delitem__(self, k):
        del self._d[k]

    def __contains__(self, k):
        return k in self._d

    def __len__(self):
        return len(self._d)

    def __iter__(self):
        return iter(self._d.keys())

    def keys(self):
        return self._d.keys()

    def values(self):
        return self._d.values()

    def items(self):
        return self._d.items()

    def get(self, k, default=None):
        return self._d.get(k, default)

    def move_to_end(self, k, last=True):
        if k not in self._d:
            raise KeyError(str(k))
        if last:
            v = self._d.pop(k)
            self._d[k] = v
        else:
            items = [(k, self._d.pop(k))] + list(self._d.items())
            self._d.clear()
            for ik, iv in items:
                self._d[ik] = iv

    def popitem(self, last=True):
        if len(self._d) == 0:
            raise KeyError("dictionary is empty")
        if last:
            return self._d.popitem()
        k = next(iter(self._d.keys()))
        return (k, self._d.pop(k))


def namedtuple(typename, field_names):
    if isinstance(field_names, str):
        fields = [f.strip() for f in field_names.replace(",", " ").split() if f.strip()]
    else:
        fields = list(field_names)
    fields_tuple = tuple(fields)

    def __init__(self, *args, **kwargs):
        vals = list(args)
        if len(vals) > len(fields_tuple):
            raise TypeError(f"{typename}() takes {len(fields_tuple)} positional arguments but {len(vals)} were given")
        for i in range(len(vals), len(fields_tuple)):
            fname = fields_tuple[i]
            if fname in kwargs:
                vals.append(kwargs.pop(fname))
            else:
                raise TypeError(f"{typename}() missing required argument: '{fname}'")
        if kwargs:
            raise TypeError(f"{typename}() got unexpected keyword argument")
        self._values = tuple(vals)

    def _asdict(self):
        out = {}
        for i in range(len(fields_tuple)):
            out[fields_tuple[i]] = self._values[i]
        return out

    def __repr__(self):
        parts = [f"{fields_tuple[i]}={repr(self._values[i])}" for i in range(len(fields_tuple))]
        return f"{typename}(" + ", ".join(parts) + ")"

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self._values == other._values

    def __len__(self):
        return len(self._values)

    def __getitem__(self, idx):
        return self._values[idx]

    def __iter__(self):
        return iter(self._values)

    ns = {
        "_fields": fields_tuple,
        "__init__": __init__,
        "_asdict": _asdict,
        "__repr__": __repr__,
        "__eq__": __eq__,
        "__len__": __len__,
        "__getitem__": __getitem__,
        "__iter__": __iter__,
    }
    for i in range(len(fields_tuple)):
        def make_prop(idx):
            return property(lambda self: self._values[idx])
        ns[fields_tuple[i]] = make_prop(i)

    return type(typename, (), ns)
