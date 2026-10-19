package io.github.anilken.wynnlabs;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev builds only: the screenshot tool reads and sets the menu's private view state (the tab, a
 * filter, a scroll) by reflection, at tick time and never in a frame, so the release code carries
 * no accessor for it. A field that is renamed fails loudly: the run stops and puts everything back,
 * and the ShotsPreview gametest catches it.
 */
final class Peek {

    private Peek() {}

    private static final Map<String, Field> FIELDS = new HashMap<>();

    private static Field field(Class<?> c, String name) {
        return FIELDS.computeIfAbsent(c.getName() + "#" + name, k -> {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("no field " + c.getSimpleName() + "." + name, e);
            }
        });
    }

    /** {@code owner}'s field ({@code owner} a Class for a static one). */
    static Object get(Object owner, String name) {
        try {
            Field f = owner instanceof Class<?> c ? field(c, name) : field(owner.getClass(), name);
            return f.get(Modifier.isStatic(f.getModifiers()) ? null : owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Object owner, String name, Object value) {
        try {
            Field f = owner instanceof Class<?> c ? field(c, name) : field(owner.getClass(), name);
            f.set(Modifier.isStatic(f.getModifiers()) ? null : owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Some fields of {@code owner} as they are now, copied deep enough that changes after do not reach them. */
    static Map<String, Object> save(Object owner, String... names) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String n : names) {
            out.put(n, copy(get(owner, n)));
        }
        return out;
    }

    /** The fields back as {@link #save} found them (lists and arrays filled in place when final). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void restore(Object owner, Map<String, Object> saved) {
        for (Map.Entry<String, Object> e : saved.entrySet()) {
            Field f = field(owner.getClass(), e.getKey());
            Object v = e.getValue();
            if (Modifier.isFinal(f.getModifiers())) {
                Object now = get(owner, e.getKey());
                if (now instanceof List l && v instanceof List s) {
                    l.clear();
                    l.addAll(s);
                } else if (now instanceof int[][] a && v instanceof int[][] s) {
                    for (int i = 0; i < a.length && i < s.length; i++) {
                        System.arraycopy(s[i], 0, a[i], 0, Math.min(a[i].length, s[i].length));
                    }
                }
                continue;
            }
            set(owner, e.getKey(), copy(v));
        }
    }

    private static Object copy(Object v) {
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (v instanceof int[][] a) {
            int[][] c = new int[a.length][];
            for (int i = 0; i < a.length; i++) {
                c[i] = a[i].clone();
            }
            return c;
        }
        return v;
    }
}
