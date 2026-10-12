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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stores a bounded list of recently opened projects outside project directories. */
public final class RecentProjectStore {
    public static final int MAX_RECENT_PROJECTS = 12;
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storageFile;
    private final EasyCodeProjectStore projectStore;

    public RecentProjectStore() {
        this(Path.of(System.getProperty("user.home"), ".easycode", "recent-projects.json"),
                new EasyCodeProjectStore());
    }

    public RecentProjectStore(Path storageFile, EasyCodeProjectStore projectStore) {
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile")
                .toAbsolutePath().normalize();
        this.projectStore = Objects.requireNonNull(projectStore, "projectStore");
    }

    public synchronized List<EasyCodeProject> load() throws IOException {
        if (!Files.isRegularFile(storageFile)) {
            return List.of();
        }
        if (Files.size(storageFile) > MAX_FILE_BYTES) {
            throw new IOException("recent projects file exceeds the 256 KB size limit");
        }
        try {
            RecentProjects recent = MAPPER.readValue(storageFile.toFile(), RecentProjects.class);
            if (recent == null || recent.formatVersion() != FORMAT_VERSION
                    || recent.projectDirectories() == null) {
                throw new IOException("invalid recent projects file");
            }
            List<EasyCodeProject> projects = new ArrayList<>();
            for (String value : recent.projectDirectories()) {
                if (value == null || projects.size() == MAX_RECENT_PROJECTS) {
                    continue;
                }
                try {
                    EasyCodeProject project = projectStore.open(Path.of(value));
                    if (projects.stream().noneMatch(existing -> existing.rootDirectory()
                            .equals(project.rootDirectory()))) {
                        projects.add(project);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A removed or invalid project should not prevent startup.
                }
            }
            return List.copyOf(projects);
        } catch (JsonProcessingException exception) {
            throw new IOException("invalid recent projects file: " + exception.getMessage(), exception);
        }
    }

    public synchronized void record(EasyCodeProject project) throws IOException {
        EasyCodeProject safeProject = Objects.requireNonNull(project, "project");
        List<String> directories = new ArrayList<>();
        directories.add(safeProject.rootDirectory().toString());
        for (EasyCodeProject existing : load()) {
            if (!existing.rootDirectory().equals(safeProject.rootDirectory())
                    && directories.size() < MAX_RECENT_PROJECTS) {
                directories.add(existing.rootDirectory().toString());
            }
        }
        writeAtomically(new RecentProjects(FORMAT_VERSION, List.copyOf(directories)));
    }

    private void writeAtomically(RecentProjects recent) throws IOException {
        Path parent = storageFile.getParent();
        if (parent == null) {
            throw new IOException("recent projects file must have a parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "recent-projects-", ".tmp");
        try {
            MAPPER.writeValue(temporary.toFile(), recent);
            try {
                Files.move(temporary, storageFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record RecentProjects(int formatVersion, List<String> projectDirectories) {
    }
}
