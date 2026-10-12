package com.easycode.project.model;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** An opened easyCode project directory. */
public record EasyCodeProject(UUID projectId, String name, Path rootDirectory) {
    public EasyCodeProject {
        Objects.requireNonNull(projectId, "projectId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory")
                .toAbsolutePath().normalize();
    }

    public Path canvasDirectory() {
        return rootDirectory.resolve("canvases");
    }
}
