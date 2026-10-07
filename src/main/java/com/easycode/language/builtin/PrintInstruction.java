package com.easycode.language.builtin;

import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.runtime.EasyCodeExecutionContext;

/** Built-in console instruction with simple ${variable} interpolation. */
public record PrintInstruction(String id, String template, boolean newline)
        implements EasyCodeInstruction {
    public PrintInstruction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("instruction id must not be blank");
        }
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
    }

    public static PrintInstruction line(String id, String template) {
        return new PrintInstruction(id, template, true);
    }

    @Override
    public void execute(EasyCodeExecutionContext context) {
        String value = context.interpolate(template);
        if (newline) {
            context.println(value);
        } else {
            context.print(value);
        }
    }
}
