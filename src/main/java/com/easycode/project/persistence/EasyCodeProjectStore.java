package com.easycode.project.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.easycode.project.model.EasyCodeProject;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/** Creates and opens project directories using the local easyCode project format. */
public final class EasyCodeProjectStore {
    public static final String PROJECT_FILE_NAME = "easycode.project.json";
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_PROJECT_FILE_BYTES = 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public EasyCodeProject create(Path parentDirectory, String projectName) throws IOException {
        Path parent = Objects.requireNonNull(parentDirectory, "parentDirectory")
                .toAbsolutePath().normalize();
        String name = validateName(projectName);
        Files.createDirectories(parent);
        Path root = parent.resolve(name).normalize();
        if (!parent.equals(root.getParent())) {
            throw new IOException("project name must be a single directory name");
        }
        Files.createDirectory(root);
        try {
            Files.createDirectory(root.resolve("canvases"));
            Manifest manifest = new Manifest(FORMAT_VERSION, UUID.randomUUID(), name);
            writeAtomically(root.resolve(PROJECT_FILE_NAME), manifest);
            return new EasyCodeProject(manifest.projectId(), manifest.name(), root);
        } catch (IOException | RuntimeException exception) {
            deleteCreatedTree(root);
            throw exception;
        }
    }

    public EasyCodeProject open(Path projectDirectory) throws IOException {
        Path root = Objects.requireNonNull(projectDirectory, "projectDirectory")
                .toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IOException("project path is not a directory");
        }
        Path projectFile = root.resolve(PROJECT_FILE_NAME);
        if (!Files.isRegularFile(projectFile)) {
            throw new IOException("not an easyCode project: " + PROJECT_FILE_NAME + " is missing");
        }
        if (Files.size(projectFile) > MAX_PROJECT_FILE_BYTES) {
            throw new IOException("project file exceeds the 64 KB size limit");
        }
        try {
            Manifest manifest = MAPPER.readValue(projectFile.toFile(), Manifest.class);
            validateManifest(manifest);
            Files.createDirectories(root.resolve("canvases"));
            return new EasyCodeProject(manifest.projectId(), manifest.name(), root);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IOException("invalid easyCode project file: " + exception.getMessage(), exception);
        }
    }

    private static void validateManifest(Manifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("project manifest is missing");
        }
        if (manifest.formatVersion() != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported project format version: "
                    + manifest.formatVersion());
        }
        if (manifest.projectId() == null) {
            throw new IllegalArgumentException("projectId is missing");
        }
        validateName(manifest.name());
    }

    private static String validateName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        String name = value.trim();
        Path path = Path.of(name);
        if (path.isAbsolute() || path.getNameCount() != 1
                || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("project name must be a single directory name");
        }
        return name;
    }

    private static void writeAtomically(Path target, Manifest manifest) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "easycode-project-", ".tmp");
        try {
            MAPPER.writeValue(temporary.toFile(), manifest);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void deleteCreatedTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Manifest(int formatVersion, UUID projectId, String name) {
    }
}
