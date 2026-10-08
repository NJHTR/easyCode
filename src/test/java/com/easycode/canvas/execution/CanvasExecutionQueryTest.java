package com.easycode.canvas.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasExecutionQueryTest {
    @Test
    void resultQueriesPublishedAndNamedValuesWithoutLosingNull() {
        UUID canvasId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID valuePort = UUID.randomUUID();
        UUID nullPort = UUID.randomUUID();
        CanvasNodeExecutionTrace trace = new CanvasNodeExecutionTrace(nodeId, "test",
                CanvasExecutionStatus.SUCCEEDED, Map.of(), nullableMap(nullPort, null), List.of(),
                Duration.ZERO, "");
        CanvasExecutionResult result = new CanvasExecutionResult(canvasId, CanvasExecutionStatus.SUCCEEDED,
                List.of(nodeId), List.of(trace), List.of(), values(valuePort, "value", nullPort, null),
                namedValues("answer", null), null, "", Duration.ZERO);

        assertEquals("value", result.output(valuePort).requireValue());
        assertTrue(result.output(nullPort).present());
        assertNull(result.output(nullPort).requireValue());
        assertTrue(result.namedOutput("answer").present());
        assertNull(result.namedOutput("answer").requireValue());
        assertFalse(result.output(UUID.randomUUID()).present());
        assertFalse(result.namedOutput("missing").present());
        assertSame(trace, result.trace(nodeId).orElseThrow());
    }

    @Test
    void traceQueriesInputsAndOutputsByPort() {
        UUID inputPort = UUID.randomUUID();
        UUID outputPort = UUID.randomUUID();
        CanvasNodeExecutionTrace trace = new CanvasNodeExecutionTrace(UUID.randomUUID(), "test",
                CanvasExecutionStatus.SUCCEEDED, Map.of(inputPort, "input"), nullableMap(outputPort, null),
                List.of(), Duration.ZERO, "");

        assertEquals("input", trace.input(inputPort).requireValue());
        assertTrue(trace.output(outputPort).present());
        assertNull(trace.output(outputPort).requireValue());
        assertFalse(trace.input(UUID.randomUUID()).present());
    }

    @Test
    void missingLookupCannotBeRequired() {
        CanvasValueLookup missing = CanvasValueLookup.missing();

        assertFalse(missing.present());
        assertThrows(IllegalStateException.class, missing::requireValue);
        assertThrows(IllegalArgumentException.class, () -> new CanvasValueLookup(false, "invalid"));
    }

    private static Map<UUID, Object> values(UUID firstKey, Object firstValue, UUID secondKey, Object secondValue) {
        java.util.LinkedHashMap<UUID, Object> values = new java.util.LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static Map<UUID, Object> nullableMap(UUID key, Object value) {
        java.util.LinkedHashMap<UUID, Object> values = new java.util.LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    private static Map<String, Object> namedValues(String key, Object value) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        values.put(key, value);
        return values;
    }
}
