# Pure-Python functools module for Jython3.

_MISSING = object()


def reduce(function, iterable, initializer=_MISSING):
    it = iter(iterable)
    if initializer is _MISSING:
        try:
            value = next(it)
        except StopIteration:
            raise TypeError("reduce() of empty iterable with no initial value")
    else:
        value = initializer
    for element in it:
        value = function(value, element)
    return value


def partial(func, *args, **kwargs):
    def wrapper(*more_args, **more_kwargs):
        merged_kw = kwargs.copy()
        merged_kw.update(more_kwargs)
        combined = list(args) + list(more_args)
        return func(*combined, **merged_kw)
    return wrapper


def lru_cache(maxsize=128):
    def decorator(fn):
        cache = {}
        order = []

        def wrapper(*args, **kwargs):
            key = (args, tuple(sorted(kwargs.items())))
            if key in cache:
                return cache[key]
            res = fn(*args, **kwargs)
            if maxsize is None or maxsize > 0:
                if maxsize is not None and len(order) >= maxsize:
                    oldest = order.pop(0)
                    if oldest in cache:
                        cache.pop(oldest)
                cache[key] = res
                order.append(key)
            return res

        return wrapper
    return decorator
