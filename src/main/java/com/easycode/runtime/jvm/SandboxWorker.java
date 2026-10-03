package com.easycode.runtime.jvm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Runs in a separate JVM. Keep the worker API narrow: do not pass host objects
 * or the application classpath to code supplied by a user.
 */
public final class SandboxWorker {
    private static final int MAX_INPUT_CHARS = 8_192;

    private SandboxWorker() {
    }

    public static void main(String[] args) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String operation;
            String payload;
            if (args.length == 2) {
                operation = args[0];
                payload = args[1];
            } else {
                operation = reader.readLine();
                payload = reader.readLine();
            }
            if (operation == null || payload == null) {
                throw new IllegalArgumentException("worker input must contain operation and payload");
            }
            if (payload.length() > MAX_INPUT_CHARS) {
                throw new IllegalArgumentException("payload is too large");
            }

            System.out.print(execute(operation, payload));
        } catch (Exception exception) {
            System.err.println("WORKER_ERROR: " + exception.getMessage());
            System.exit(2);
        }
    }

    private static String execute(String operation, String payload) throws InterruptedException {
        return switch (operation) {
            case "uppercase" -> payload.toUpperCase(Locale.ROOT);
            case "sum" -> sum(payload);
            case "sleep" -> sleep(payload);
            default -> throw new IllegalArgumentException("unsupported operation: " + operation);
        };
    }

    private static String sum(String payload) {
        if (payload.isBlank()) {
            throw new IllegalArgumentException("sum requires comma-separated numbers");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (String value : payload.split(",", -1)) {
            total = total.add(new BigDecimal(value.trim()));
        }
        return total.stripTrailingZeros().toPlainString();
    }

    private static String sleep(String payload) throws InterruptedException {
        long milliseconds;
        try {
            milliseconds = Long.parseLong(payload);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("sleep requires milliseconds", exception);
        }
        if (milliseconds < 0 || milliseconds > 60_000) {
            throw new IllegalArgumentException("sleep must be between 0 and 60000 milliseconds");
        }
        Thread.sleep(milliseconds);
        return "slept " + milliseconds + " ms";
    }
}
