package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInstance;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyStaticMethod;
import org.example.jython3.runtime.PyStr;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure Java-backed {@code datetime} module providing {@code date}, {@code time},
 * {@code datetime}, and {@code timedelta} with arithmetic, comparisons, ISO formatting,
 * and {@code strftime} support.
 */
public final class DatetimeModule {
    private DatetimeModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();

        final PyClass timedeltaCls = new PyClass("timedelta", null, new HashMap<>());
        final PyClass dateCls = new PyClass("date", null, new HashMap<>());
        final PyClass timeCls = new PyClass("time", null, new HashMap<>());
        final PyClass datetimeCls = new PyClass("datetime", dateCls, new HashMap<>());

        // timedelta
        timedeltaCls.setMember("__eq__", new PyBuiltinFunction("timedelta.__eq__", a -> {
            if (!(a[1] instanceof PyInstance o) || !o.isInstanceOf(timedeltaCls)) return PyBool.FALSE;
            return PyBool.of(totalMicros(a[0]) == totalMicros(o));
        }));
        timedeltaCls.setMember("__lt__", new PyBuiltinFunction("timedelta.__lt__", a -> {
            if (!(a[1] instanceof PyInstance o) || !o.isInstanceOf(timedeltaCls)) return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
            return PyBool.of(totalMicros(a[0]) < totalMicros(o));
        }));
        timedeltaCls.setMember("__add__", new PyBuiltinFunction("timedelta.__add__", a -> {
            if (a[1] instanceof PyInstance o && o.isInstanceOf(timedeltaCls)) {
                return makeTimedelta(timedeltaCls, totalMicros(a[0]) + totalMicros(o));
            }
            if (a[1] instanceof PyInstance o && o.isInstanceOf(datetimeCls)) {
                return addMicrosToDateTime(datetimeCls, o, totalMicros(a[0]));
            }
            if (a[1] instanceof PyInstance o && o.isInstanceOf(dateCls)) {
                return addDaysToDate(dateCls, o, totalMicros(a[0]) / 86_400_000_000L);
            }
            return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
        }));
        timedeltaCls.setMember("__sub__", new PyBuiltinFunction("timedelta.__sub__", a -> {
            if (a[1] instanceof PyInstance o && o.isInstanceOf(timedeltaCls)) {
                return makeTimedelta(timedeltaCls, totalMicros(a[0]) - totalMicros(o));
            }
            return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
        }));
        timedeltaCls.setMember("__neg__", new PyBuiltinFunction("timedelta.__neg__", a ->
                makeTimedelta(timedeltaCls, -totalMicros(a[0]))));
        timedeltaCls.setMember("total_seconds", new PyBuiltinFunction("timedelta.total_seconds", a ->
                new PyFloat(totalMicros(a[0]) / 1_000_000.0)));
        timedeltaCls.setMember("__repr__", new PyBuiltinFunction("timedelta.__repr__", a -> {
            PyInstance inst = (PyInstance) a[0];
            return new PyStr("datetime.timedelta(days=" + inst.fields().get("days")
                    + ", seconds=" + inst.fields().get("seconds")
                    + ", microseconds=" + inst.fields().get("microseconds") + ")");
        }));
        timedeltaCls.setMember("__str__", new PyBuiltinFunction("timedelta.__str__", a -> {
            long us = totalMicros(a[0]);
            long days = Math.floorDiv(us, 86_400_000_000L);
            long rem = Math.floorMod(us, 86_400_000_000L);
            long secs = rem / 1_000_000L;
            long micros = rem % 1_000_000L;
            long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
            String base = String.format("%d:%02d:%02d", h, m, s)
                    + (micros != 0 ? String.format(".%06d", micros) : "");
            if (days == 0) return new PyStr(base);
            return new PyStr(days + (Math.abs(days) == 1 ? " day, " : " days, ") + base);
        }));

        // date
        dateCls.setMember("today", new PyStaticMethod(new PyBuiltinFunction("date.today", a -> makeDate(dateCls, LocalDate.now()))));
        dateCls.setMember("fromisoformat", new PyStaticMethod(new PyBuiltinFunction("date.fromisoformat", a -> {
            PyOps.checkArgCount("fromisoformat", a, 1);
            try { return makeDate(dateCls, LocalDate.parse(a[0].toString())); }
            catch (Exception e) { throw PyErrors.error("ValueError", "Invalid isoformat string: '" + a[0] + "'"); }
        })));
        dateCls.setMember("isoformat", new PyBuiltinFunction("date.isoformat", a ->
                new PyStr(toLocalDate(a[0]).toString())));
        dateCls.setMember("weekday", new PyBuiltinFunction("date.weekday", a ->
                PyInt.of(toLocalDate(a[0]).getDayOfWeek().getValue() - 1)));
        dateCls.setMember("isoweekday", new PyBuiltinFunction("date.isoweekday", a ->
                PyInt.of(toLocalDate(a[0]).getDayOfWeek().getValue())));
        dateCls.setMember("strftime", new PyBuiltinFunction("date.strftime", a -> {
            PyOps.checkArgCount("strftime", a, 2);
            return new PyStr(pyStrftime(a[1].toString(), toLocalDate(a[0]).atStartOfDay()));
        }));
        dateCls.setMember("__eq__", new PyBuiltinFunction("date.__eq__", a -> {
            if (!(a[1] instanceof PyInstance o) || !o.isInstanceOf(dateCls)) return PyBool.FALSE;
            if (o.isInstanceOf(datetimeCls) != ((PyInstance) a[0]).isInstanceOf(datetimeCls)) return PyBool.FALSE;
            if (o.isInstanceOf(datetimeCls)) return PyBool.of(toLocalDateTime(a[0]).equals(toLocalDateTime(o)));
            return PyBool.of(toLocalDate(a[0]).equals(toLocalDate(o)));
        }));
        dateCls.setMember("__lt__", new PyBuiltinFunction("date.__lt__", a -> {
            if (!(a[1] instanceof PyInstance o) || !o.isInstanceOf(dateCls)) return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
            if (o.isInstanceOf(datetimeCls) != ((PyInstance) a[0]).isInstanceOf(datetimeCls)) {
                throw PyErrors.error("TypeError", "can't compare datetime.datetime to datetime.date");
            }
            if (o.isInstanceOf(datetimeCls)) return PyBool.of(toLocalDateTime(a[0]).isBefore(toLocalDateTime(o)));
            return PyBool.of(toLocalDate(a[0]).isBefore(toLocalDate(o)));
        }));
        dateCls.setMember("__add__", new PyBuiltinFunction("date.__add__", a -> {
            if (a[1] instanceof PyInstance td && td.isInstanceOf(timedeltaCls)) {
                return addDaysToDate(dateCls, a[0], totalMicros(td) / 86_400_000_000L);
            }
            return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
        }));
        dateCls.setMember("__sub__", new PyBuiltinFunction("date.__sub__", a -> {
            if (a[1] instanceof PyInstance td && td.isInstanceOf(timedeltaCls)) {
                return addDaysToDate(dateCls, a[0], -(totalMicros(td) / 86_400_000_000L));
            }
            if (a[1] instanceof PyInstance other && other.isInstanceOf(dateCls) && !other.isInstanceOf(datetimeCls)) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(toLocalDate(other), toLocalDate(a[0]));
                return makeTimedelta(timedeltaCls, days * 86_400_000_000L);
            }
            return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
        }));
        dateCls.setMember("__str__", new PyBuiltinFunction("date.__str__", a -> new PyStr(toLocalDate(a[0]).toString())));
        dateCls.setMember("__repr__", new PyBuiltinFunction("date.__repr__", a -> {
            LocalDate d = toLocalDate(a[0]);
            return new PyStr("datetime.date(" + d.getYear() + ", " + d.getMonthValue() + ", " + d.getDayOfMonth() + ")");
        }));

        // time
        timeCls.setMember("isoformat", new PyBuiltinFunction("time.isoformat", a ->
                new PyStr(toLocalTime(a[0]).format(DateTimeFormatter.ISO_LOCAL_TIME))));
        timeCls.setMember("__str__", new PyBuiltinFunction("time.__str__", a ->
                new PyStr(toLocalTime(a[0]).format(DateTimeFormatter.ISO_LOCAL_TIME))));
        timeCls.setMember("__eq__", new PyBuiltinFunction("time.__eq__", a -> {
            if (!(a[1] instanceof PyInstance o) || !o.isInstanceOf(timeCls)) return PyBool.FALSE;
            return PyBool.of(toLocalTime(a[0]).equals(toLocalTime(o)));
        }));

        // datetime (subclass of date)
        datetimeCls.setMember("now", new PyStaticMethod(new PyBuiltinFunction("datetime.now", a -> makeDateTime(datetimeCls, LocalDateTime.now()))));
        datetimeCls.setMember("utcnow", new PyStaticMethod(new PyBuiltinFunction("datetime.utcnow", a -> makeDateTime(datetimeCls, LocalDateTime.now(ZoneOffset.UTC)))));
        datetimeCls.setMember("fromtimestamp", new PyStaticMethod(new PyBuiltinFunction("datetime.fromtimestamp", a -> {
            PyOps.checkArgCount("fromtimestamp", a, 1);
            double secs = PyOps.toDouble(a[0]);
            long whole = (long) Math.floor(secs);
            long nanos = (long) Math.round((secs - whole) * 1_000_000_000.0);
            return makeDateTime(datetimeCls, LocalDateTime.ofInstant(Instant.ofEpochSecond(whole, nanos), java.time.ZoneId.systemDefault()));
        })));
        datetimeCls.setMember("fromisoformat", new PyStaticMethod(new PyBuiltinFunction("datetime.fromisoformat", a -> {
            PyOps.checkArgCount("fromisoformat", a, 1);
            try { return makeDateTime(datetimeCls, LocalDateTime.parse(a[0].toString().replace(' ', 'T'))); }
            catch (Exception e) { throw PyErrors.error("ValueError", "Invalid isoformat string: '" + a[0] + "'"); }
        })));
        datetimeCls.setMember("isoformat", new PyBuiltinFunction("datetime.isoformat", a -> {
            String sep = a.length >= 2 ? a[1].toString() : "T";
            LocalDateTime dt = toLocalDateTime(a[0]);
            return new PyStr(dt.toLocalDate() + sep + dt.toLocalTime());
        }));
        datetimeCls.setMember("date", new PyBuiltinFunction("datetime.date", a -> makeDate(dateCls, toLocalDateTime(a[0]).toLocalDate())));
        datetimeCls.setMember("time", new PyBuiltinFunction("datetime.time", a -> makeTime(timeCls, toLocalDateTime(a[0]).toLocalTime())));
        datetimeCls.setMember("timestamp", new PyBuiltinFunction("datetime.timestamp", a -> {
            Instant inst = toLocalDateTime(a[0]).atZone(java.time.ZoneId.systemDefault()).toInstant();
            return new PyFloat(inst.getEpochSecond() + inst.getNano() / 1_000_000_000.0);
        }));
        datetimeCls.setMember("strftime", new PyBuiltinFunction("datetime.strftime", a -> {
            PyOps.checkArgCount("strftime", a, 2);
            return new PyStr(pyStrftime(a[1].toString(), toLocalDateTime(a[0])));
        }));
        datetimeCls.setMember("__add__", new PyBuiltinFunction("datetime.__add__", a -> {
            if (a[1] instanceof PyInstance td && td.isInstanceOf(timedeltaCls)) {
                return addMicrosToDateTime(datetimeCls, a[0], totalMicros(td));
            }
            return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
        }));
        datetimeCls.setMember("__sub__", new PyBuiltinFunction("datetime.__sub__", a -> {
            if (a[1] instanceof PyInstance td && td.isInstanceOf(timedeltaCls)) {
                return addMicrosToDateTime(datetimeCls, a[0], -totalMicros(td));
            }
            if (a[1] instanceof PyInstance other && other.isInstanceOf(datetimeCls)) {
                long us = java.time.temporal.ChronoUnit.MICROS.between(toLocalDateTime(other), toLocalDateTime(a[0]));
                return makeTimedelta(timedeltaCls, us);
            }
            return org.example.jython3.runtime.PyNotImplemented.INSTANCE;
        }));
        datetimeCls.setMember("__str__", new PyBuiltinFunction("datetime.__str__", a -> {
            LocalDateTime dt = toLocalDateTime(a[0]);
            return new PyStr(dt.toLocalDate() + " " + dt.toLocalTime());
        }));
        datetimeCls.setMember("__repr__", new PyBuiltinFunction("datetime.__repr__", a -> {
            LocalDateTime dt = toLocalDateTime(a[0]);
            return new PyStr("datetime.datetime(" + dt.getYear() + ", " + dt.getMonthValue() + ", " + dt.getDayOfMonth()
                    + ", " + dt.getHour() + ", " + dt.getMinute() + ", " + dt.getSecond() + ")");
        }));

        // Constructors exposed on module
        attrs.put("timedelta", wrapFactoryWithStatics("datetime.timedelta", timedeltaCls, (args, kwargs) -> {
            double days = argOrKw(args, kwargs, 0, "days", 0);
            double seconds = argOrKw(args, kwargs, 1, "seconds", 0);
            double micros = argOrKw(args, kwargs, 2, "microseconds", 0);
            double millis = argOrKw(args, kwargs, 3, "milliseconds", 0);
            double minutes = argOrKw(args, kwargs, 4, "minutes", 0);
            double hours = argOrKw(args, kwargs, 5, "hours", 0);
            double weeks = argOrKw(args, kwargs, 6, "weeks", 0);
            long totalUs = Math.round(((weeks * 7 + days) * 86400.0 + hours * 3600.0 + minutes * 60.0 + seconds) * 1_000_000.0
                    + millis * 1_000.0 + micros);
            return makeTimedelta(timedeltaCls, totalUs);
        }));

        attrs.put("date", wrapFactoryWithStatics("datetime.date", dateCls, (args, kwargs) -> {
            int y = PyOps.toInt(kwargs.getOrDefault("year", args.length >= 1 ? args[0] : PyNull.INSTANCE));
            int m = PyOps.toInt(kwargs.getOrDefault("month", args.length >= 2 ? args[1] : PyNull.INSTANCE));
            int d = PyOps.toInt(kwargs.getOrDefault("day", args.length >= 3 ? args[2] : PyNull.INSTANCE));
            try { return makeDate(dateCls, LocalDate.of(y, m, d)); }
            catch (Exception e) { throw PyErrors.error("ValueError", e.getMessage()); }
        }));

        attrs.put("time", wrapFactoryWithStatics("datetime.time", timeCls, (args, kwargs) -> {
            int h = (int) argOrKw(args, kwargs, 0, "hour", 0);
            int m = (int) argOrKw(args, kwargs, 1, "minute", 0);
            int s = (int) argOrKw(args, kwargs, 2, "second", 0);
            int us = (int) argOrKw(args, kwargs, 3, "microsecond", 0);
            try { return makeTime(timeCls, LocalTime.of(h, m, s, us * 1000)); }
            catch (Exception e) { throw PyErrors.error("ValueError", e.getMessage()); }
        }));

        attrs.put("datetime", wrapFactoryWithStatics("datetime.datetime", datetimeCls, (args, kwargs) -> {
            int y = PyOps.toInt(kwargs.getOrDefault("year", args.length >= 1 ? args[0] : PyNull.INSTANCE));
            int mo = PyOps.toInt(kwargs.getOrDefault("month", args.length >= 2 ? args[1] : PyNull.INSTANCE));
            int d = PyOps.toInt(kwargs.getOrDefault("day", args.length >= 3 ? args[2] : PyNull.INSTANCE));
            int h = (int) argOrKw(args, kwargs, 3, "hour", 0);
            int mi = (int) argOrKw(args, kwargs, 4, "minute", 0);
            int s = (int) argOrKw(args, kwargs, 5, "second", 0);
            int us = (int) argOrKw(args, kwargs, 6, "microsecond", 0);
            try { return makeDateTime(datetimeCls, LocalDateTime.of(y, mo, d, h, mi, s, us * 1000)); }
            catch (Exception e) { throw PyErrors.error("ValueError", e.getMessage()); }
        }));

        return new PyModule("datetime", attrs);
    }

    private static PyClass wrapFactoryWithStatics(
            String name, PyClass cls, java.util.function.BiFunction<PyObject[], Map<String, PyObject>, PyObject> ctor) {
        // Attach __new__ / callable behavior or return PyClass with __call__? Wait: calling PyClass calls __init__!
        // Setting __init__ on cls makes cls(...) work natively AND exposes class methods like date.today(), datetime.now()!
        cls.setMember("__init__", new PyBuiltinFunction(name + ".__init__", (args, kwargs) -> {
            PyInstance self = (PyInstance) args[0];
            PyObject[] ctorArgs = new PyObject[args.length - 1];
            System.arraycopy(args, 1, ctorArgs, 0, ctorArgs.length);
            PyInstance built = (PyInstance) ctor.apply(ctorArgs, kwargs);
            self.fields().putAll(built.fields());
            return PyNull.INSTANCE;
        }));
        return cls;
    }

    private static double argOrKw(PyObject[] args, Map<String, PyObject> kwargs, int idx, String kw, double def) {
        if (kwargs.containsKey(kw)) return PyOps.toDouble(kwargs.get(kw));
        if (args.length > idx && !(args[idx] instanceof PyNull)) return PyOps.toDouble(args[idx]);
        return def;
    }

    private static PyInstance makeTimedelta(PyClass cls, long totalMicros) {
        long days = Math.floorDiv(totalMicros, 86_400_000_000L);
        long rem = Math.floorMod(totalMicros, 86_400_000_000L);
        long secs = rem / 1_000_000L;
        long micros = rem % 1_000_000L;
        PyInstance inst = new PyInstance(cls);
        inst.setField("days", PyInt.of((int) days));
        inst.setField("seconds", PyInt.of((int) secs));
        inst.setField("microseconds", PyInt.of((int) micros));
        return inst;
    }

    private static long totalMicros(PyObject o) {
        PyInstance inst = (PyInstance) o;
        long d = ((PyInt) inst.fields().get("days")).value();
        long s = ((PyInt) inst.fields().get("seconds")).value();
        long us = ((PyInt) inst.fields().get("microseconds")).value();
        return (d * 86_400L + s) * 1_000_000L + us;
    }

    private static PyInstance makeDate(PyClass cls, LocalDate d) {
        PyInstance inst = new PyInstance(cls);
        inst.setField("year", PyInt.of(d.getYear()));
        inst.setField("month", PyInt.of(d.getMonthValue()));
        inst.setField("day", PyInt.of(d.getDayOfMonth()));
        return inst;
    }

    private static PyInstance makeTime(PyClass cls, LocalTime t) {
        PyInstance inst = new PyInstance(cls);
        inst.setField("hour", PyInt.of(t.getHour()));
        inst.setField("minute", PyInt.of(t.getMinute()));
        inst.setField("second", PyInt.of(t.getSecond()));
        inst.setField("microsecond", PyInt.of(t.getNano() / 1000));
        return inst;
    }

    private static PyInstance makeDateTime(PyClass cls, LocalDateTime dt) {
        PyInstance inst = new PyInstance(cls);
        inst.setField("year", PyInt.of(dt.getYear()));
        inst.setField("month", PyInt.of(dt.getMonthValue()));
        inst.setField("day", PyInt.of(dt.getDayOfMonth()));
        inst.setField("hour", PyInt.of(dt.getHour()));
        inst.setField("minute", PyInt.of(dt.getMinute()));
        inst.setField("second", PyInt.of(dt.getSecond()));
        inst.setField("microsecond", PyInt.of(dt.getNano() / 1000));
        return inst;
    }

    private static LocalDate toLocalDate(PyObject o) {
        PyInstance inst = (PyInstance) o;
        return LocalDate.of(
                ((PyInt) inst.fields().get("year")).value(),
                ((PyInt) inst.fields().get("month")).value(),
                ((PyInt) inst.fields().get("day")).value());
    }

    private static LocalTime toLocalTime(PyObject o) {
        PyInstance inst = (PyInstance) o;
        return LocalTime.of(
                ((PyInt) inst.fields().get("hour")).value(),
                ((PyInt) inst.fields().get("minute")).value(),
                ((PyInt) inst.fields().get("second")).value(),
                ((PyInt) inst.fields().get("microsecond")).value() * 1000);
    }

    private static LocalDateTime toLocalDateTime(PyObject o) {
        return LocalDateTime.of(toLocalDate(o), toLocalTime(o));
    }

    private static PyInstance addDaysToDate(PyClass dateCls, PyObject dateObj, long days) {
        return makeDate(dateCls, toLocalDate(dateObj).plusDays(days));
    }

    private static PyInstance addMicrosToDateTime(PyClass dtCls, PyObject dtObj, long micros) {
        return makeDateTime(dtCls, toLocalDateTime(dtObj).plusNanos(micros * 1000L));
    }

    private static String pyStrftime(String fmt, LocalDateTime dt) {
        var sb = new StringBuilder(fmt.length() + 16);
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '%' && i + 1 < fmt.length()) {
                char code = fmt.charAt(++i);
                switch (code) {
                    case 'Y' -> sb.append(String.format("%04d", dt.getYear()));
                    case 'm' -> sb.append(String.format("%02d", dt.getMonthValue()));
                    case 'd' -> sb.append(String.format("%02d", dt.getDayOfMonth()));
                    case 'H' -> sb.append(String.format("%02d", dt.getHour()));
                    case 'M' -> sb.append(String.format("%02d", dt.getMinute()));
                    case 'S' -> sb.append(String.format("%02d", dt.getSecond()));
                    case 'f' -> sb.append(String.format("%06d", dt.getNano() / 1000));
                    case '%' -> sb.append('%');
                    default -> sb.append('%').append(code);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
