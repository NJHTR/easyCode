package com.easycode.language.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/** Mutable state scoped to one easyCode program run. */
public final class EasyCodeExecutionContext {
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final StringBuilder consoleOutput = new StringBuilder();

    public void setVariable(String name, Object value) {
        validateName(name);
        variables.put(name, value);
    }

    public Object variable(String name) {
        validateName(name);
        if (!variables.containsKey(name)) {
            throw new IllegalArgumentException("unknown variable: " + name);
        }
        return variables.get(name);
    }

    public void print(Object value) {
        consoleOutput.append(String.valueOf(value));
    }

    public void println(Object value) {
        consoleOutput.append(String.valueOf(value)).append(System.lineSeparator());
    }

    public Map<String, Object> variablesSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public String consoleOutput() {
        return consoleOutput.toString();
    }

    public String interpolate(String template) {
        Objects.requireNonNull(template, "template");
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (cursor < template.length()) {
            int start = template.indexOf("${", cursor);
            if (start < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            result.append(template, cursor, start);
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("unterminated variable expression");
            }
            String name = template.substring(start + 2, end);
            result.append(String.valueOf(variable(name)));
            cursor = end + 1;
        }
        return result.toString();
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
    }
}
