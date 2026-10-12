package com.easycode.canvas.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.validation.CanvasGraphValidator;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

/** Reads and atomically writes versioned local Canvas MVP documents. */
public final class CanvasDocumentStore {
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public CanvasMvpDocument load(Path path) throws IOException {
        Path source = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        long fileSize = Files.size(source);
        if (fileSize > MAX_FILE_BYTES) {
            throw new IOException("canvas file exceeds the 5 MB size limit");
        }
        try {
            CanvasMvpDocument document = MAPPER.readValue(source.toFile(), CanvasMvpDocument.class);
            validateConfigurationValues(document);
            validateGraph(document);
            return document;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IOException("invalid canvas file: " + exception.getMessage(), exception);
        }
    }

    public void save(Path path, CanvasMvpDocument document) throws IOException {
        Path target = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        CanvasMvpDocument safeDocument = Objects.requireNonNull(document, "document");
        validateConfigurationValues(safeDocument);
        validateGraph(safeDocument);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("canvas file must have a parent directory");
        }
        Files.createDirectories(parent);
        String prefix = target.getFileName().toString();
        if (prefix.length() < 3) {
            prefix = (prefix + "___").substring(0, 3);
        }
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            MAPPER.writeValue(temporary.toFile(), safeDocument);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateConfigurationValues(CanvasMvpDocument document) throws IOException {
        for (CanvasMvpWorkspace.PlacedNode placedNode : document.nodes()) {
            for (Map.Entry<String, Object> entry : placedNode.node().configuration().entrySet()) {
                validateJsonValue(entry.getValue(), "node configuration '" + entry.getKey() + "'", 0);
            }
        }
    }

    private static void validateGraph(CanvasMvpDocument document) throws IOException {
        CanvasDefinition definition = new CanvasDefinition(document.canvasId(), document.name(),
                document.nodes().stream().map(CanvasMvpWorkspace.PlacedNode::node).toList(),
                document.connections());
        try {
            new CanvasGraphValidator().validate(definition);
        } catch (IllegalArgumentException exception) {
            throw new IOException("canvas graph is invalid: " + exception.getMessage(), exception);
        }
    }

    private static void validateJsonValue(Object value, String location, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException(location + " exceeds the supported configuration nesting depth");
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigInteger
                || value instanceof BigDecimal || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return;
        }
        if (value instanceof Float number) {
            if (Float.isFinite(number)) {
                return;
            }
            throw new IOException(location + " contains a non-finite number");
        }
        if (value instanceof Double number) {
            if (Double.isFinite(number)) {
                return;
            }
            throw new IOException(location + " contains a non-finite number");
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException(location + " contains an object key that is not text");
                }
                validateJsonValue(entry.getValue(), location + "." + key, depth + 1);
            }
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                validateJsonValue(item, location, depth + 1);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                validateJsonValue(java.lang.reflect.Array.get(value, index), location, depth + 1);
            }
            return;
        }
        throw new IOException(location + " contains a value that cannot be saved as JSON: "
                + value.getClass().getName());
    }
}
