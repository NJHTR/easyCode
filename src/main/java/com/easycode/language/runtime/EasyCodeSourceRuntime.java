package com.easycode.language.runtime;

import com.easycode.language.api.EasyCodeRuntime;
import com.easycode.language.model.EasyCodeExecutionResult;
import com.easycode.language.parser.EasyCodeSourceParser;

import java.util.Objects;
import java.util.UUID;

/** Runs easyCode source text through the parser and the configured interpreter. */
public final class EasyCodeSourceRuntime {
    private final EasyCodeSourceParser parser;
    private final EasyCodeRuntime runtime;

    public EasyCodeSourceRuntime() {
        this(new EasyCodeSourceParser(), new InProcessEasyCodeRuntime());
    }

    public EasyCodeSourceRuntime(EasyCodeSourceParser parser, EasyCodeRuntime runtime) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public EasyCodeExecutionResult execute(UUID programId, String source) {
        Objects.requireNonNull(programId, "programId");
        return runtime.execute(programId, parser.parse(source));
    }
}
