package com.easycode.project.persistence;

import com.easycode.project.model.EasyCodeProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasyCodeProjectStoreTest {
    @TempDir
    Path temporaryDirectory;

    private final EasyCodeProjectStore store = new EasyCodeProjectStore();

    @Test
    void createsAndReopensProjectWithStableIdentity() throws IOException {
        EasyCodeProject created = store.create(temporaryDirectory, "First Project");

        EasyCodeProject opened = store.open(created.rootDirectory());

        assertEquals(created.projectId(), opened.projectId());
        assertEquals("First Project", opened.name());
        assertEquals(temporaryDirectory.resolve("First Project"), opened.rootDirectory());
        assertTrue(Files.isDirectory(opened.canvasDirectory()));
        assertTrue(Files.isRegularFile(opened.rootDirectory()
                .resolve(EasyCodeProjectStore.PROJECT_FILE_NAME)));
    }

    @Test
    void refusesToReplaceAnExistingDirectory() throws IOException {
        Path existing = Files.createDirectory(temporaryDirectory.resolve("Existing"));

        assertThrows(IOException.class, () -> store.create(temporaryDirectory, "Existing"));
        assertFalse(Files.exists(existing.resolve(EasyCodeProjectStore.PROJECT_FILE_NAME)));
    }

    @Test
    void rejectsNamesThatCouldEscapeTheChosenParent() {
        assertThrows(IllegalArgumentException.class,
                () -> store.create(temporaryDirectory, "../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> store.create(temporaryDirectory, "   "));
    }

    @Test
    void rejectsDirectoriesWithoutProjectMetadata() throws IOException {
        Path ordinaryFolder = Files.createDirectory(temporaryDirectory.resolve("ordinary"));

        IOException failure = assertThrows(IOException.class, () -> store.open(ordinaryFolder));

        assertTrue(failure.getMessage().contains("not an easyCode project"));
    }

    @Test
    void rejectsMalformedProjectMetadata() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("broken"));
        Files.writeString(root.resolve(EasyCodeProjectStore.PROJECT_FILE_NAME), "not json");

        assertThrows(IOException.class, () -> store.open(root));
    }

    @Test
    void recentProjectsMoveOpenedProjectToTheFrontWithoutDuplicates() throws IOException {
        EasyCodeProject first = store.create(temporaryDirectory, "First");
        EasyCodeProject second = store.create(temporaryDirectory, "Second");
        RecentProjectStore recent = new RecentProjectStore(
                temporaryDirectory.resolve("user-config/recent.json"), store);

        recent.record(first);
        recent.record(second);
        recent.record(first);

        assertEquals(java.util.List.of(first.rootDirectory(), second.rootDirectory()),
                recent.load().stream().map(EasyCodeProject::rootDirectory).toList());
    }

    @Test
    void recentProjectsIgnoreDirectoriesThatAreNoLongerProjects() throws IOException {
        EasyCodeProject project = store.create(temporaryDirectory, "Temporary");
        RecentProjectStore recent = new RecentProjectStore(
                temporaryDirectory.resolve("user-config/recent.json"), store);
        recent.record(project);
        Files.delete(project.canvasDirectory());
        Files.delete(project.rootDirectory().resolve(EasyCodeProjectStore.PROJECT_FILE_NAME));
        Files.delete(project.rootDirectory());

        assertTrue(recent.load().isEmpty());
    }
}
