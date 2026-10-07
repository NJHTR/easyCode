package com.easycode.language.expression;

import com.easycode.language.api.EasyCodeExpression;
import com.easycode.language.runtime.EasyCodeExecutionContext;

import java.util.Objects;

/** Small composable expression building blocks for easyCode programs. */
public final class EasyCodeExpressions {
    private EasyCodeExpressions() {
    }

    public static <T> EasyCodeExpression<T> constant(T value) {
        return ignored -> value;
    }

    public static <T> EasyCodeExpression<T> variable(String name, Class<T> type) {
        Objects.requireNonNull(type, "type");
        validateName(name);
        return context -> castVariable(context, name, type);
    }

    public static EasyCodeExpression<Integer> add(
            EasyCodeExpression<Integer> left,
            EasyCodeExpression<Integer> right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return context -> left.evaluate(context) + right.evaluate(context);
    }

    public static EasyCodeExpression<Boolean> equalTo(
            EasyCodeExpression<?> left,
            EasyCodeExpression<?> right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return context -> Objects.equals(left.evaluate(context), right.evaluate(context));
    }

    private static <T> T castVariable(EasyCodeExecutionContext context, String name, Class<T> type) {
        Object value = context.variable(name);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "variable '" + name + "' must be " + type.getSimpleName());
        }
        return type.cast(value);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
    }
}
