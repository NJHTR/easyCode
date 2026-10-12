package com.easycode.canvas.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** A lazy filesystem view for the folder explicitly opened by the user. */
final class CanvasProjectExplorer extends JPanel {
    private static final String CANVAS_SUFFIX = ".easycanvas.json";

    private final Consumer<Path> canvasOpener;
    private final JLabel rootLabel = new JLabel("未打开文件夹");
    private final JTree tree = new JTree(new DefaultMutableTreeNode("打开一个文件夹以浏览项目"));
    private Path root;

    CanvasProjectExplorer(Runnable openFolderAction, Consumer<Path> canvasOpener) {
        super(new BorderLayout(0, 6));
        this.canvasOpener = Objects.requireNonNull(canvasOpener, "canvasOpener");
        setPreferredSize(new Dimension(240, 0));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 6));

        JPanel heading = new JPanel(new BorderLayout(6, 0));
        rootLabel.setToolTipText("当前项目文件夹");
        JButton openFolder = new JButton("打开文件夹");
        openFolder.addActionListener(event -> openFolderAction.run());
        heading.add(rootLabel, BorderLayout.CENTER);
        heading.add(openFolder, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                loadChildren((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    TreePath selection = tree.getPathForLocation(event.getX(), event.getY());
                    if (selection != null && selection.getLastPathComponent() instanceof DefaultMutableTreeNode node
                            && node.getUserObject() instanceof ProjectPath entry
                            && Files.isRegularFile(entry.path())
                            && entry.path().getFileName().toString().endsWith(CANVAS_SUFFIX)) {
                        Path path = entry.path();
                        canvasOpener.accept(path);
                    }
                }
            }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    void setRoot(Path selectedRoot) throws IOException {
        Path normalized = Objects.requireNonNull(selectedRoot, "selectedRoot").toRealPath();
        if (!Files.isDirectory(normalized)) {
            throw new IOException("selected project path is not a folder");
        }
        root = normalized;
        rootLabel.setText(normalized.getFileName() == null ? normalized.toString()
                : normalized.getFileName().toString());
        rootLabel.setToolTipText(normalized.toString());
        DefaultMutableTreeNode rootNode = directoryNode(normalized);
        tree.setModel(new DefaultTreeModel(rootNode));
        loadChildren(rootNode);
        tree.expandRow(0);
    }

    Path root() {
        return root;
    }

    void refresh() {
        if (root == null) {
            return;
        }
        try {
            setRoot(root);
        } catch (IOException ignored) {
            root = null;
            rootLabel.setText("未打开文件夹");
            tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("文件夹不可用")));
        }
    }

    private void loadChildren(DefaultMutableTreeNode parent) {
        if (!(parent.getUserObject() instanceof ProjectPath parentEntry)
                || !Files.isDirectory(parentEntry.path())) {
            return;
        }
        Path directory = parentEntry.path();
        if (parent.getChildCount() != 1 || !"".equals(
                ((DefaultMutableTreeNode) parent.getChildAt(0)).getUserObject())) {
            return;
        }
        parent.removeAllChildren();
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> sorted = entries
                    .filter(path -> !isExcluded(path))
                    .sorted(Comparator.<Path, Boolean>comparing(Files::isDirectory).reversed()
                            .thenComparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();
            for (Path entry : sorted) {
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(new ProjectPath(entry));
                if (Files.isDirectory(entry)) {
                    child.add(new DefaultMutableTreeNode(""));
                }
                parent.add(child);
            }
        } catch (IOException exception) {
            parent.add(new DefaultMutableTreeNode("无法读取此文件夹"));
        }
        ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(parent);
    }

    private static DefaultMutableTreeNode directoryNode(Path path) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ProjectPath(path));
        node.add(new DefaultMutableTreeNode(""));
        return node;
    }

    private static boolean isExcluded(Path path) {
        String name = path.getFileName().toString();
        if (name.equals(".git") || name.equals(".idea") || name.equals("target")) {
            return true;
        }
        try {
            return Files.isHidden(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    private record ProjectPath(Path path) {
        @Override
        public String toString() {
            Path fileName = path.getFileName();
            return fileName == null ? path.toString() : fileName.toString();
        }
    }
}
