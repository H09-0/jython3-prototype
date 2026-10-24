package org.example.jython3.security;

/**
 * Renders method and constructor parameter lists as JVM descriptors so access
 * policies can distinguish overloads. Return types are omitted because Java
 * does not allow two methods with the same name and parameter types.
 */
public final class JavaDescriptors {
    private JavaDescriptors() {}

    public static String of(Class<?>[] parameterTypes) {
        var sb = new StringBuilder("(");
        if (parameterTypes != null) {
            for (Class<?> type : parameterTypes) sb.append(describe(type));
        }
        return sb.append(")V").toString();
    }

    private static String describe(Class<?> type) {
        if (type == null) return "Ljava/lang/Object;";
        if (type.isArray()) return "[" + describe(type.getComponentType());
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        return switch (type.getName()) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> throw new IllegalArgumentException("unknown primitive: " + type);
        };
    }
}
