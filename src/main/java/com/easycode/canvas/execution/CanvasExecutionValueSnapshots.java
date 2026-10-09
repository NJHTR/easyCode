package com.easycode.canvas.execution;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CanvasExecutionValueSnapshots {
    private CanvasExecutionValueSnapshots() {
    }

    static Map<UUID, Object> snapshot(Map<UUID, Object> values, String name) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null)) {
            throw new IllegalArgumentException(name + " cannot contain a null port id");
        }
        Map<UUID, Object> snapshot = new LinkedHashMap<>();
        IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
        values.forEach((portId, value) -> snapshot.put(portId, copy(value, copies)));
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object copy(Object value, IdentityHashMap<Object, Object> copies) {
        if (value == null || isImmutable(value)) {
            return value;
        }
        Object existing = copies.get(value);
        if (existing != null) {
            return existing;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            Map<Object, Object> snapshot = Collections.unmodifiableMap(copy);
            copies.put(value, snapshot);
            map.forEach((key, item) -> copy.put(copy(key, copies), copy(item, copies)));
            return snapshot;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            List<Object> snapshot = Collections.unmodifiableList(copy);
            copies.put(value, snapshot);
            list.forEach(item -> copy.add(copy(item, copies)));
            return snapshot;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            Set<Object> snapshot = Collections.unmodifiableSet(copy);
            copies.put(value, snapshot);
            set.forEach(item -> copy.add(copy(item, copies)));
            return snapshot;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>();
            List<Object> snapshot = Collections.unmodifiableList(copy);
            copies.put(value, snapshot);
            collection.forEach(item -> copy.add(copy(item, copies)));
            return snapshot;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            List<Object> snapshot = Collections.unmodifiableList(copy);
            copies.put(value, snapshot);
            for (int index = 0; index < length; index++) {
                copy.add(copy(Array.get(value, index), copies));
            }
            return snapshot;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return value;
    }

    private static boolean isImmutable(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal
                || value instanceof UUID
                || value instanceof URI
                || value instanceof Enum<?>
                || value instanceof Class<?>
                || value instanceof TemporalAccessor
                || value instanceof TemporalAmount;
    }
}
