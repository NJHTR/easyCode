package com.easycode.tool.model;

import java.util.regex.Pattern;

/** Stable public description of one callable tool capability. */
public record ToolDefinition(String name, String description, String inputSchema) {
    private static final Pattern NAME_PATTERN =
            Pattern.compile("[a-z0-9]+(?:\\.[a-z0-9]+)*");

    public ToolDefinition {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name must use lowercase dot-separated segments");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("inputSchema must not be blank");
        }
        description = description.trim();
        inputSchema = inputSchema.trim();
    }
}
