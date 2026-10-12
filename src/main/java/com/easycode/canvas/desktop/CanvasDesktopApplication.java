package com.easycode.canvas.desktop;

import com.easycode.canvas.execution.CanvasExecutionEvent;
import com.easycode.canvas.execution.CanvasExecutionEventType;
import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import com.easycode.project.model.EasyCodeProject;
import com.easycode.project.persistence.EasyCodeProjectStore;
import com.easycode.project.persistence.RecentProjectStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JFileChooser;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;
import javax.swing.event.ChangeEvent;

/** Standalone Swing entry point for the first visual graph execution MVP. */
public final class CanvasDesktopApplication {
    private static final Color INK = new Color(39, 48, 59);
    private static final Color MUTED = new Color(106, 118, 132);
    private static final Color CANVAS = new Color(245, 247, 249);
    private static final Color TEAL = new Color(0, 126, 121);

    private CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
    private final CanvasDocumentStore documentStore = new CanvasDocumentStore();
    private final EasyCodeProjectStore projectStore = new EasyCodeProjectStore();
    private final RecentProjectStore recentProjects = new RecentProjectStore();
    private final Preferences layoutPreferences = Preferences.userRoot().node("com.easycode.desktop");
    private Map<UUID, String> nodeStates = new HashMap<>();
    private Map<UUID, Map<UUID, Object>> liveOutputs = new HashMap<>();
    private final JTextArea console = new JTextArea();
    private final JLabel status = new JLabel("Ready");
    private GraphPanel graph = new GraphPanel();
    private final JTabbedPane canvasTabs = new JTabbedPane();
    private final CanvasProjectExplorer projectExplorer =
            new CanvasProjectExplorer(this::openProjectFolder, this::openCanvasPath);
    private JFrame frame;
    private JSplitPane editorAndOutput;
    private JSplitPane projectAndEditor;
    private JPanel outputWindow;
    private JDialog nodePaletteWindow;
    private JCheckBoxMenuItem nodeLibraryMenuItem;
    private boolean projectWindowVisible = layoutPreferences.getBoolean("project.visible", true);
    private boolean consoleWindowVisible = layoutPreferences.getBoolean("console.visible", true);
    private boolean canvasTabsConfigured;
    private UUID pendingOutputNode;
    private String pendingOutputName;
    private boolean running;
    private Path currentFile;
    private EasyCodeProject currentProject;
    private long savedRevision;
    private final Map<java.awt.Component, CanvasTabState> tabs = new LinkedHashMap<>();
    private CanvasTabState activeTab;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new CanvasDesktopApplication().show());
    }

    private void show() {
        frame = new JFrame("easyCode Canvas MVP");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (running) {
                    JOptionPane.showMessageDialog(frame, "Wait for the current run to finish before closing.",
                            "Run in progress", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                if (confirmAllUnsavedChanges()) {
                    saveLayoutPreferences();
                    frame.dispose();
                }
            }
        });
        frame.setMinimumSize(new Dimension(960, 650));
        frame.setSize(1180, 760);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.setJMenuBar(null);
        showWelcome();
        frame.setVisible(true);
    }

    private void saveLayoutPreferences() {
        layoutPreferences.putBoolean("project.visible", projectWindowVisible);
        layoutPreferences.putBoolean("console.visible", consoleWindowVisible);
        if (projectAndEditor != null) {
            layoutPreferences.putInt("project.divider", projectAndEditor.getDividerLocation());
        }
        if (editorAndOutput != null) {
            layoutPreferences.putInt("console.divider", editorAndOutput.getDividerLocation());
        }
    }

    private void ensureInitialTab() {
        if (activeTab == null) {
            installTab(new CanvasTabState(workspace, graph, currentFile, savedRevision,
                    nodeStates, liveOutputs));
        }
    }

    private void installTab(CanvasTabState state) {
        activeTab = state;
        workspace = state.workspace;
        graph = state.graph;
        currentFile = state.file;
        savedRevision = state.savedRevision;
        nodeStates = state.nodeStates;
        liveOutputs = state.liveOutputs;
    }

    private void captureActiveTab() {
        if (activeTab == null) {
            return;
        }
        activeTab.workspace = workspace;
        activeTab.graph = graph;
        activeTab.file = currentFile;
        activeTab.savedRevision = savedRevision;
        activeTab.nodeStates = nodeStates;
        activeTab.liveOutputs = liveOutputs;
        activeTab.consoleText = console.getText();
    }

    private void addCanvasTab(CanvasTabState state) {
        JScrollPane scroll = new JScrollPane(state.graph);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        tabs.put(scroll, state);
        canvasTabs.addTab(state.workspace.document().name(), scroll);
        canvasTabs.setSelectedComponent(scroll);
        installTab(state);
        updateDocumentTitle();
    }

    private void switchCanvasTab(int index) {
        if (index < 0 || index >= canvasTabs.getTabCount()) {
            return;
        }
        captureActiveTab();
        CanvasTabState state = tabs.get(canvasTabs.getComponentAt(index));
        if (state == null) {
            return;
        }
        installTab(state);
        pendingOutputNode = null;
        pendingOutputName = null;
        console.setText(state.consoleText);
        updateDocumentTitle();
        graph.reload(workspace.nodes());
        graph.setPendingPort(null, null);
    }

    private void resetTabs() {
        captureActiveTab();
        tabs.clear();
        canvasTabs.removeAll();
        activeTab = null;
    }

    private void showWelcome() {
        if (frame == null) {
            return;
        }
        frame.setTitle("easyCode");
        frame.setJMenuBar(null);
        frame.getContentPane().removeAll();
        JPanel page = new JPanel(new BorderLayout(0, 24));
        page.setBorder(BorderFactory.createEmptyBorder(48, 64, 48, 64));
        JLabel brand = new JLabel("easyCode");
        brand.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        brand.setForeground(INK);
        page.add(brand, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(40, 0));
        JPanel actions = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 10, 0);
        addWelcomeAction(actions, c, "新建项目", this::createProject);
        c.gridy++;
        addWelcomeAction(actions, c, "打开项目", this::openProjectDialog);
        c.gridy++;
        addWelcomeAction(actions, c, "打开单个画布", this::openCanvas);
        content.add(actions, BorderLayout.WEST);

        JPanel recentPanel = new JPanel(new BorderLayout(0, 8));
        recentPanel.add(new JLabel("最近打开的项目"), BorderLayout.NORTH);
        JPanel recentList = new JPanel();
        recentList.setLayout(new javax.swing.BoxLayout(recentList, javax.swing.BoxLayout.Y_AXIS));
        try {
            List<EasyCodeProject> projects = recentProjects.load();
            if (projects.isEmpty()) {
                recentList.add(new JLabel("还没有打开过项目"));
            } else {
                for (EasyCodeProject project : projects) {
                    JButton item = new JButton("<html><b>" + escapeHtml(project.name())
                            + "</b><br><span style='color:#6a7684'>"
                            + escapeHtml(project.rootDirectory().toString()) + "</span></html>");
                    item.setHorizontalAlignment(JButton.LEFT);
                    item.setBackground(Color.WHITE);
                    item.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
                    item.addActionListener(event -> openProject(project.rootDirectory()));
                    recentList.add(item);
                }
            }
        } catch (IOException exception) {
            recentList.add(new JLabel("最近项目列表暂不可用：" + exception.getMessage()));
        }
        recentPanel.add(new JScrollPane(recentList), BorderLayout.CENTER);
        content.add(recentPanel, BorderLayout.CENTER);
        page.add(content, BorderLayout.CENTER);
        frame.add(page, BorderLayout.CENTER);
        frame.revalidate();
        frame.repaint();
    }

    private static void addWelcomeAction(JPanel panel, GridBagConstraints constraints,
                                         String label, Runnable action) {
        JButton button = new JButton(label);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setPreferredSize(new Dimension(210, 42));
        button.addActionListener(event -> action.run());
        panel.add(button, constraints);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void createProject() {
        if (running || !confirmAllUnsavedChanges()) {
            return;
        }
        JTextField name = new JTextField("My Project", 24);
        JTextField parent = new JTextField(System.getProperty("user.home"), 28);
        JButton browse = new JButton("浏览...");
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(parent.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                parent.setText(chooser.getSelectedFile().toPath().toString());
            }
        });
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("项目名称"), c);
        c.gridx = 1;
        form.add(name, c);
        c.gridx = 0;
        c.gridy = 1;
        form.add(new JLabel("保存位置"), c);
        c.gridx = 1;
        form.add(parent, c);
        c.gridx = 2;
        form.add(browse, c);
        if (JOptionPane.showConfirmDialog(frame, form, "新建 easyCode 项目",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            EasyCodeProject project = projectStore.create(Path.of(parent.getText()), name.getText());
            recentProjects.record(project);
            currentProject = project;
            resetTabs();
            workspace.reset(project.name());
            currentFile = project.canvasDirectory().resolve(project.name() + ".easycanvas.json");
            documentStore.save(currentFile, workspace.document());
            savedRevision = workspace.revision();
            graph = new GraphPanel();
            nodeStates = new HashMap<>();
            liveOutputs = new HashMap<>();
            addCanvasTab(new CanvasTabState(workspace, graph, currentFile, savedRevision,
                    nodeStates, liveOutputs));
            showWorkspace();
            projectExplorer.setRoot(project.rootDirectory());
            graph.reload(workspace.nodes());
            updateDocumentTitle();
        } catch (IOException | RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private void openProjectDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("打开 easyCode 项目文件夹");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            openProject(chooser.getSelectedFile().toPath());
        }
    }

    private void openProject(Path root) {
        if (running || !confirmAllUnsavedChanges()) {
            return;
        }
        try {
            EasyCodeProject project = projectStore.open(root);
            recentProjects.record(project);
            currentProject = project;
            resetTabs();
            projectExplorer.setRoot(project.rootDirectory());
            Path canvas = findFirstCanvas(project);
            if (canvas != null) {
                openCanvasPath(canvas);
            } else {
                workspace.reset(project.name());
                currentFile = project.canvasDirectory().resolve(project.name() + ".easycanvas.json");
                documentStore.save(currentFile, workspace.document());
                savedRevision = workspace.revision();
                graph = new GraphPanel();
                nodeStates = new HashMap<>();
                liveOutputs = new HashMap<>();
                addCanvasTab(new CanvasTabState(workspace, graph, currentFile, savedRevision,
                        nodeStates, liveOutputs));
                showWorkspace();
                graph.reload(workspace.nodes());
                updateDocumentTitle();
            }
            showWorkspace();
            status.setText("项目已打开: " + project.name());
        } catch (IOException | RuntimeException exception) {
            JOptionPane.showMessageDialog(frame, exception.getMessage(), "无法打开项目",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Path findFirstCanvas(EasyCodeProject project) throws IOException {
        try (var files = Files.list(project.canvasDirectory())) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".easycanvas.json"))
                    .sorted()
                    .findFirst().orElse(null);
        }
    }

    private void showWorkspace() {
        frame.setJMenuBar(menuBar());
        frame.getContentPane().removeAll();
        frame.add(toolbar(), BorderLayout.NORTH);
        frame.add(workbench(), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(190, 198, 205)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        footer.add(status, BorderLayout.WEST);
        frame.add(footer, BorderLayout.SOUTH);
        frame.revalidate();
        frame.repaint();
    }

    private void returnToWelcome() {
        if (running || !confirmAllUnsavedChanges()) {
            return;
        }
        currentProject = null;
        currentFile = null;
        resetTabs();
        showWelcome();
    }

    private JMenuBar menuBar() {
        JMenuBar menus = new JMenuBar();
        JMenu file = new JMenu("文件");
        addMenuItem(file, "打开项目...", this::openProjectDialog);
        addMenuItem(file, "返回欢迎页", this::returnToWelcome);
        file.addSeparator();
        addMenuItem(file, "新建画布", this::newCanvas);
        addMenuItem(file, "打开...", this::openCanvas);
        addMenuItem(file, "保存", () -> saveCanvas());
        addMenuItem(file, "关闭当前画布", this::closeCurrentCanvas);
        file.addSeparator();
        addMenuItem(file, "退出", () -> frame.dispatchEvent(
                new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        JMenu edit = new JMenu("编辑");
        addMenuItem(edit, "添加节点...", () -> showNodePicker(centerOfCanvas()));
        JMenu view = new JMenu("视图");
        JCheckBoxMenuItem projectItem = new JCheckBoxMenuItem("项目", projectWindowVisible);
        projectItem.addActionListener(event -> setProjectWindowVisible(projectItem.isSelected()));
        view.add(projectItem);
        JCheckBoxMenuItem consoleItem = new JCheckBoxMenuItem("控制台", consoleWindowVisible);
        consoleItem.addActionListener(event -> setConsoleWindowVisible(consoleItem.isSelected()));
        view.add(consoleItem);
        nodeLibraryMenuItem = new JCheckBoxMenuItem("节点库", false);
        nodeLibraryMenuItem.addActionListener(event -> showNodePaletteWindow(nodeLibraryMenuItem.isSelected()));
        view.add(nodeLibraryMenuItem);
        JMenu execute = new JMenu("执行");
        addMenuItem(execute, "运行画布", this::runGraph);
        JMenu help = new JMenu("帮助");
        addMenuItem(help, "关于 easyCode", () -> JOptionPane.showMessageDialog(frame,
                "easyCode Canvas MVP", "关于", JOptionPane.INFORMATION_MESSAGE));
        menus.add(file);
        menus.add(edit);
        menus.add(view);
        menus.add(execute);
        menus.add(new JMenu("工具"));
        menus.add(help);
        return menus;
    }

    private static void addMenuItem(JMenu menu, String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> action.run());
        menu.add(item);
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(190, 198, 205)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        JToolBar actions = new JToolBar();
        actions.setFloatable(false);
        actions.setBorderPainted(false);
        addAction(actions, "新建", this::newCanvas);
        addAction(actions, "打开", this::openCanvas);
        addAction(actions, "保存", () -> saveCanvas());
        actions.addSeparator(new Dimension(8, 1));
        addAction(actions, "添加节点", () -> showNodePicker(centerOfCanvas()));
        addAction(actions, "运行", this::runGraph, true);
        bar.add(actions, BorderLayout.WEST);
        return bar;
    }

    private JSplitPane workbench() {
        if (canvasTabs.getTabCount() == 0) {
            addCanvasTab(new CanvasTabState(workspace, graph, currentFile, savedRevision,
                    nodeStates, liveOutputs));
        }
        if (!canvasTabsConfigured) {
            canvasTabs.addChangeListener(event -> {
                if (!running) {
                    switchCanvasTab(canvasTabs.getSelectedIndex());
                }
            });
            canvasTabsConfigured = true;
        }
        JTabbedPane outputTabs = new JTabbedPane();
        outputWindow = inspector();
        outputTabs.addTab("控制台", outputWindow);
        editorAndOutput = new JSplitPane(JSplitPane.VERTICAL_SPLIT, canvasTabs, outputTabs);
        editorAndOutput.setResizeWeight(0.78);
        editorAndOutput.setDividerLocation(0.78);
        editorAndOutput.setOneTouchExpandable(true);
        editorAndOutput.setBorder(BorderFactory.createEmptyBorder());
        projectAndEditor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectExplorer, editorAndOutput);
        projectAndEditor.setResizeWeight(0.0);
        projectAndEditor.setDividerLocation(250);
        projectAndEditor.setOneTouchExpandable(true);
        projectAndEditor.setBorder(BorderFactory.createEmptyBorder());
        int projectDivider = layoutPreferences.getInt("project.divider", 250);
        int consoleDivider = layoutPreferences.getInt("console.divider", -1);
        projectAndEditor.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> saveLayoutPreferences());
        editorAndOutput.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> saveLayoutPreferences());
        SwingUtilities.invokeLater(() -> {
            if (projectWindowVisible) {
                projectAndEditor.setDividerLocation(Math.max(160, projectDivider));
            }
            if (consoleWindowVisible && consoleDivider >= 0) {
                editorAndOutput.setDividerLocation(consoleDivider);
            }
            setProjectWindowVisible(projectWindowVisible);
            setConsoleWindowVisible(consoleWindowVisible);
        });
        return projectAndEditor;
    }

    private JPanel nodePalette() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setPreferredSize(new Dimension(245, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 6));
        JPanel heading = new JPanel(new BorderLayout());
        heading.add(new JLabel("节点"), BorderLayout.WEST);
        JTextField search = new JTextField();
        search.setToolTipText("搜索节点");
        search.setPreferredSize(new Dimension(110, 25));
        heading.add(search, BorderLayout.EAST);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("节点库");
        DefaultMutableTreeNode basics = new DefaultMutableTreeNode("基础");
        basics.add(new DefaultMutableTreeNode(new PaletteNode("常量", CanvasMvpWorkspace.constantNodeType())));
        basics.add(new DefaultMutableTreeNode(new PaletteNode("加法", CanvasMvpWorkspace.addNodeType())));
        basics.add(new DefaultMutableTreeNode(new PaletteNode("输出", CanvasMvpWorkspace.printNodeType())));
        root.add(basics);
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.expandRow(0);
        tree.expandRow(1);
        tree.setDragEnabled(true);
        tree.setTransferHandler(new PaletteTransferHandler());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    PaletteNode selected = selectedPaletteNode(tree);
                    if (selected != null) {
                        addPaletteNode(selected.nodeType(), null);
                    }
                }
            }
        });
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                filterPalette(tree, search.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                filterPalette(tree, search.getText());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                filterPalette(tree, search.getText());
            }
        });
        panel.add(heading, BorderLayout.NORTH);
        panel.add(new JScrollPane(tree), BorderLayout.CENTER);
        return panel;
    }

    private static PaletteNode selectedPaletteNode(JTree tree) {
        Object selected = tree.getLastSelectedPathComponent();
        if (selected instanceof DefaultMutableTreeNode treeNode
                && treeNode.getUserObject() instanceof PaletteNode paletteNode) {
            return paletteNode;
        }
        return null;
    }

    private static void filterPalette(JTree tree, String query) {
        String filter = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("节点库");
        DefaultMutableTreeNode basics = new DefaultMutableTreeNode("基础");
        for (PaletteNode item : java.util.List.of(
                new PaletteNode("常量", CanvasMvpWorkspace.constantNodeType()),
                new PaletteNode("加法", CanvasMvpWorkspace.addNodeType()),
                new PaletteNode("输出", CanvasMvpWorkspace.printNodeType()))) {
            if (filter.isEmpty() || item.label().toLowerCase(java.util.Locale.ROOT).contains(filter)
                    || item.nodeType().toLowerCase(java.util.Locale.ROOT).contains(filter)) {
                basics.add(new DefaultMutableTreeNode(item));
            }
        }
        if (basics.getChildCount() > 0) {
            root.add(basics);
        }
        tree.setModel(new javax.swing.tree.DefaultTreeModel(root));
        tree.expandRow(0);
        tree.expandRow(1);
    }

    private final class PaletteTransferHandler extends TransferHandler {
        @Override
        protected Transferable createTransferable(javax.swing.JComponent component) {
            PaletteNode selected = selectedPaletteNode((JTree) component);
            return selected == null ? null : new StringSelection(selected.nodeType());
        }

        @Override
        public int getSourceActions(javax.swing.JComponent component) {
            return COPY;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return !running && support.isDataFlavorSupported(DataFlavor.stringFlavor)
                    && support.getComponent() == graph;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                String nodeType = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                java.awt.Point location = support.getDropLocation().getDropPoint();
                addPaletteNode(nodeType, location);
                return true;
            } catch (Exception exception) {
                showError(exception.getMessage());
                return false;
            }
        }
    }

    private record PaletteNode(String label, String nodeType) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void openProjectFolder() {
        if (running) {
            return;
        }
        JFileChooser chooser = new JFileChooser(projectExplorer.root() == null ? null
                : projectExplorer.root().toFile());
        chooser.setDialogTitle("打开项目文件夹");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Path selected = chooser.getSelectedFile().toPath();
            EasyCodeProject project = projectStore.open(selected);
            recentProjects.record(project);
            currentProject = project;
            projectExplorer.setRoot(project.rootDirectory());
            status.setText("项目: " + project.name());
        } catch (IOException exception) {
            showError(exception.getMessage());
        }
    }

    private void setProjectWindowVisible(boolean visible) {
        projectWindowVisible = visible;
        projectAndEditor.setLeftComponent(visible ? projectExplorer : null);
        projectAndEditor.setDividerSize(visible ? 6 : 0);
        projectAndEditor.setDividerLocation(visible
                ? layoutPreferences.getInt("project.divider", 250) : 0);
        saveLayoutPreferences();
    }

    private void setConsoleWindowVisible(boolean visible) {
        consoleWindowVisible = visible;
        editorAndOutput.setBottomComponent(visible ? outputTabs() : null);
        editorAndOutput.setDividerSize(visible ? 6 : 0);
        if (visible) {
            int savedDivider = layoutPreferences.getInt("console.divider", -1);
            if (savedDivider >= 0) {
                editorAndOutput.setDividerLocation(savedDivider);
            } else {
                editorAndOutput.setDividerLocation(0.78);
            }
        } else {
            editorAndOutput.setDividerLocation(1.0);
        }
        saveLayoutPreferences();
    }

    private JTabbedPane outputTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("控制台", outputWindow);
        return tabs;
    }

    private void showNodePaletteWindow(boolean visible) {
        if (nodePaletteWindow == null) {
            nodePaletteWindow = new JDialog(frame, "节点库", false);
            nodePaletteWindow.setContentPane(nodePalette());
            nodePaletteWindow.setSize(270, 430);
            nodePaletteWindow.setLocation(frame.getX() + 40, frame.getY() + 90);
            nodePaletteWindow.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            nodePaletteWindow.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    nodePaletteMenuClosed();
                }
            });
        }
        nodePaletteWindow.setVisible(visible);
    }

    private void nodePaletteMenuClosed() {
        nodePaletteWindow.setVisible(false);
        nodeLibraryMenuItem.setSelected(false);
    }

    private java.awt.Point centerOfCanvas() {
        java.awt.Rectangle visible = graph.getVisibleRect();
        return new java.awt.Point(visible.x + visible.width / 2, visible.y + visible.height / 2);
    }

    private void showNodePicker(java.awt.Point location) {
        if (running) {
            return;
        }
        JPopupMenu popup = new JPopupMenu();
        JMenu data = new JMenu("数据");
        addNodeMenuItem(data, "常量", CanvasMvpWorkspace.constantNodeType(), location);
        JMenu math = new JMenu("计算");
        addNodeMenuItem(math, "加法", CanvasMvpWorkspace.addNodeType(), location);
        JMenu output = new JMenu("输出");
        addNodeMenuItem(output, "打印到控制台", CanvasMvpWorkspace.printNodeType(), location);
        popup.add(data);
        popup.add(math);
        popup.add(output);
        popup.show(graph, Math.max(0, location.x), Math.max(0, location.y));
    }

    private void addNodeMenuItem(JMenu group, String label, String type, java.awt.Point location) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> addPaletteNode(type, location));
        group.add(item);
    }

    private void newCanvas() {
        if (running) {
            return;
        }
        JTextField name = new JTextField("My canvas", 24);
        JPanel form = new JPanel(new BorderLayout(8, 0));
        form.add(new JLabel("Name"), BorderLayout.WEST);
        form.add(name, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(frame, form, "New canvas",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Path newFile = currentProject == null ? null
                    : currentProject.canvasDirectory().resolve(fileSafeName(name.getText())
                            + ".easycanvas.json");
            if (newFile != null && Files.exists(newFile)) {
                    JOptionPane.showMessageDialog(frame, "A canvas with this name already exists.",
                            "Canvas already exists", JOptionPane.WARNING_MESSAGE);
                    return;
            }
            CanvasMvpWorkspace created = new CanvasMvpWorkspace();
            created.reset(name.getText());
            if (newFile != null) {
                documentStore.save(newFile, created.document());
                projectExplorer.refresh();
            }
            CanvasTabState state = new CanvasTabState(created, new GraphPanel(), newFile,
                    created.revision(), new HashMap<>(), new HashMap<>());
            pendingOutputNode = null;
            pendingOutputName = null;
            console.setText("");
            addCanvasTab(state);
            graph.reload(workspace.nodes());
            graph.setPendingPort(null, null);
            updateDocumentTitle();
            status.setText("Created " + workspace.document().name());
        } catch (IOException | RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private JPanel inspector() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(0, 180));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(224, 229, 234)),
                BorderFactory.createEmptyBorder(16, 14, 14, 14)));
        panel.setBackground(Color.WHITE);
        JLabel title = new JLabel("Run output");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        title.setForeground(INK);
        panel.add(title, BorderLayout.NORTH);
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setForeground(INK);
        console.setBackground(new Color(249, 250, 251));
        console.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JScrollPane(console), BorderLayout.CENTER);
        return panel;
    }

    private void addAction(java.awt.Container actions, String label, Runnable action) {
        addAction(actions, label, action, false);
    }

    private void addAction(java.awt.Container actions, String label, Runnable action, boolean primary) {
        JButton button = new JButton(label);
        button.setFocusPainted(false);
        button.setBackground(primary ? TEAL : new Color(240, 244, 246));
        button.setForeground(primary ? Color.WHITE : INK);
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        button.setEnabled(!running);
        button.addActionListener(event -> action.run());
        actions.add(button);
    }

    private void openCanvas() {
        if (running) {
            return;
        }
        JFileChooser chooser = new JFileChooser(currentProject == null ? null
                : currentProject.canvasDirectory().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("easyCode canvas (*.easycanvas.json)", "json"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        openCanvasPath(chooser.getSelectedFile().toPath());
    }

    private void openCanvasPath(Path selected) {
        if (running) {
            return;
        }
        try {
            CanvasMvpDocument document = documentStore.load(selected);
            boolean showWorkspaceAfterOpen = frame.getJMenuBar() == null;
            Path normalized = selected.toAbsolutePath().normalize();
            for (int i = 0; i < canvasTabs.getTabCount(); i++) {
                CanvasTabState existing = tabs.get(canvasTabs.getComponentAt(i));
                if (existing != null && existing.file != null
                        && existing.file.toAbsolutePath().normalize().equals(normalized)) {
                    canvasTabs.setSelectedIndex(i);
                    return;
                }
            }
            CanvasMvpWorkspace opened = new CanvasMvpWorkspace();
            opened.replace(document);
            Map<UUID, String> states = new HashMap<>();
            document.nodes().forEach(node -> states.put(node.node().nodeId(), "READY"));
            CanvasTabState state = new CanvasTabState(opened, new GraphPanel(), selected,
                    opened.revision(), states, new HashMap<>());
            pendingOutputNode = null;
            pendingOutputName = null;
            console.setText("");
            addCanvasTab(state);
            if (showWorkspaceAfterOpen) {
                showWorkspace();
            }
            graph.reload(workspace.nodes());
            graph.setPendingPort(null, null);
            if (selected.getParent() != null && projectExplorer.root() == null) {
                projectExplorer.setRoot(selected.getParent());
            }
            updateDocumentTitle();
            status.setText("Opened " + selected.getFileName());
        } catch (IOException | RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private boolean saveCanvas() {
        if (running) {
            return false;
        }
        JFileChooser chooser = canvasFileChooser();
        if (currentFile != null) {
            chooser.setSelectedFile(currentFile.toFile());
        }
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        Path selected = withCanvasExtension(chooser.getSelectedFile().toPath());
        try {
            documentStore.save(selected, workspace.document());
            currentFile = selected;
            if (selected.getParent() != null && projectExplorer.root() == null) {
                projectExplorer.setRoot(selected.getParent());
            } else {
                projectExplorer.refresh();
            }
            savedRevision = workspace.revision();
            updateDocumentTitle();
            status.setText("Saved " + selected.getFileName());
            return true;
        } catch (IOException | RuntimeException exception) {
            showError(exception.getMessage());
            return false;
        }
    }

    private boolean confirmUnsavedChanges() {
        if (workspace.revision() == savedRevision) {
            return true;
        }
        Object[] options = {"Save", "Discard", "Cancel"};
        int choice = JOptionPane.showOptionDialog(frame,
                "Save changes before leaving this canvas?",
                "Unsaved changes",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == 0) {
            return saveCanvas();
        }
        return choice == 1;
    }

    private boolean confirmAllUnsavedChanges() {
        int selected = canvasTabs.getSelectedIndex();
        for (int index = 0; index < canvasTabs.getTabCount(); index++) {
            CanvasTabState state = tabs.get(canvasTabs.getComponentAt(index));
            if (state == null || state.workspace.revision() == state.savedRevision) {
                continue;
            }
            canvasTabs.setSelectedIndex(index);
            switchCanvasTab(index);
            if (!confirmUnsavedChanges()) {
                if (selected >= 0 && selected < canvasTabs.getTabCount()) {
                    canvasTabs.setSelectedIndex(selected);
                    switchCanvasTab(selected);
                }
                return false;
            }
            captureActiveTab();
        }
        if (selected >= 0 && selected < canvasTabs.getTabCount()) {
            canvasTabs.setSelectedIndex(selected);
            switchCanvasTab(selected);
        }
        return true;
    }

    private void updateDocumentTitle() {
        if (frame == null) {
            return;
        }
        String title = "easyCode Canvas MVP - " + workspace.document().name();
        frame.setTitle(workspace.revision() == savedRevision ? title : title + " *");
        if (canvasTabs.getTabCount() > 0 && canvasTabs.getSelectedIndex() >= 0) {
            canvasTabs.setTitleAt(canvasTabs.getSelectedIndex(), workspace.document().name()
                    + (workspace.revision() == savedRevision ? "" : " *"));
        }
    }

    private void closeCurrentCanvas() {
        if (running || !confirmUnsavedChanges() || canvasTabs.getTabCount() == 0) {
            return;
        }
        int index = canvasTabs.getSelectedIndex();
        java.awt.Component component = canvasTabs.getComponentAt(index);
        tabs.remove(component);
        canvasTabs.removeTabAt(index);
        if (canvasTabs.getTabCount() == 0) {
            CanvasMvpWorkspace empty = new CanvasMvpWorkspace();
            graph = new GraphPanel();
            workspace = empty;
            currentFile = null;
            savedRevision = 0;
            nodeStates = new HashMap<>();
            liveOutputs = new HashMap<>();
            addCanvasTab(new CanvasTabState(workspace, graph, null, 0, nodeStates, liveOutputs));
        } else {
            switchCanvasTab(canvasTabs.getSelectedIndex());
        }
    }

    private JFileChooser canvasFileChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("easyCode canvas (*.easycanvas.json)", "json"));
        return chooser;
    }

    private static Path withCanvasExtension(Path selected) {
        String fileName = selected.getFileName().toString();
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".easycanvas.json")
                ? selected
                : selected.resolveSibling(fileName + ".easycanvas.json");
    }

    private static String fileSafeName(String value) {
        String name = value == null ? "canvas" : value.trim();
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isBlank() ? "canvas" : safe;
    }

    private static Object parseValue(String text) {
        String value = text == null ? "" : text.trim();
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private void addNode(String type, Map<String, Object> configuration) {
        addNode(type, configuration, null);
    }

    private void addPaletteNode(String type, java.awt.Point location) {
        Map<String, Object> configuration = type.equals(CanvasMvpWorkspace.constantNodeType())
                ? Map.of("value", "hello easyCode") : Map.of();
        addNode(type, configuration, location);
    }

    private void addNode(String type, Map<String, Object> configuration, java.awt.Point location) {
        try {
            CanvasMvpWorkspace.PlacedNode node = workspace.addNode(type, configuration);
            if (location != null) {
                UUID nodeId = node.node().nodeId();
                workspace.moveNode(nodeId, location.x, location.y);
                node = workspace.nodes().stream()
                        .filter(placed -> placed.node().nodeId().equals(nodeId))
                        .findFirst().orElseThrow();
            }
            nodeStates.put(node.node().nodeId(), "READY");
            graph.addNode(node);
            updateDocumentTitle();
            status.setText("Added " + node.displayName());
            graph.revalidate();
            graph.repaint();
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private void editConstantNode(CanvasNode node) {
        if (running) {
            return;
        }
        Object currentValue = node.configuration().get("value");
        JTextField value = new JTextField(currentValue == null ? "" : currentValue.toString(), 24);
        JPanel form = new JPanel(new BorderLayout(8, 0));
        form.add(new JLabel("Value"), BorderLayout.WEST);
        form.add(value, BorderLayout.CENTER);
        int choice = JOptionPane.showConfirmDialog(frame, form, "Edit " + node.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            workspace.updateNodeConfiguration(node.nodeId(), Map.of("value", parseValue(value.getText())));
            graph.reload(workspace.nodes());
            updateDocumentTitle();
            status.setText("Updated " + node.name());
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private void deleteNode(CanvasNode node) {
        if (running) {
            return;
        }
        long connectionCount = workspace.connections().stream()
                .filter(connection -> connection.fromNodeId().equals(node.nodeId())
                        || connection.toNodeId().equals(node.nodeId()))
                .count();
        String message = connectionCount == 0
                ? "Delete " + node.name() + "?"
                : "Delete " + node.name() + " and its " + connectionCount + " connection(s)?";
        if (JOptionPane.showConfirmDialog(frame, message, "Delete node",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            workspace.deleteNode(node.nodeId());
            nodeStates.remove(node.nodeId());
            liveOutputs.remove(node.nodeId());
            if (node.nodeId().equals(pendingOutputNode)) {
                pendingOutputNode = null;
                pendingOutputName = null;
                graph.setPendingPort(null, null);
            }
            graph.reload(workspace.nodes());
            updateDocumentTitle();
            status.setText("Deleted " + node.name());
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private void deleteConnection(CanvasConnection connection) {
        if (running) {
            return;
        }
        if (workspace.deleteConnection(connection.connectionId())) {
            graph.repaint();
            updateDocumentTitle();
            status.setText("Connection deleted");
        }
    }

    private void selectPort(CanvasNode node, CanvasPort port) {
        if (running) {
            return;
        }
        if (port.direction() == CanvasPortDirection.OUTPUT) {
            pendingOutputNode = node.nodeId();
            pendingOutputName = port.name();
            status.setText("Choose an input port to connect " + node.name() + "." + port.name());
            graph.setPendingPort(node.nodeId(), port.portId());
            return;
        }
        if (pendingOutputNode == null) {
            status.setText("Select an output port first");
            return;
        }
        try {
            CanvasConnection connection = workspace.connect(pendingOutputNode, pendingOutputName,
                    node.nodeId(), port.name());
            graph.addConnection(connection);
            updateDocumentTitle();
            status.setText("Connected " + pendingOutputName + " to " + port.name());
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
        } finally {
            pendingOutputNode = null;
            pendingOutputName = null;
            graph.setPendingPort(null, null);
        }
    }

    private void runGraph() {
        if (running) {
            return;
        }
        try {
            workspace.definition();
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
            return;
        }
        running = true;
        canvasTabs.setEnabled(false);
        setToolbarEnabled(false);
        nodeStates.replaceAll((nodeId, state) -> "WAITING");
        liveOutputs.clear();
        console.setText("");
        status.setText("Running...");
        graph.repaint();
        new SwingWorker<CanvasExecutionResult, CanvasExecutionEvent>() {
            @Override
            protected CanvasExecutionResult doInBackground() {
                return workspace.run(this::publish);
            }

            @Override
            protected void process(java.util.List<CanvasExecutionEvent> events) {
                events.forEach(CanvasDesktopApplication.this::acceptEvent);
            }

            @Override
            protected void done() {
                running = false;
                canvasTabs.setEnabled(true);
                setToolbarEnabled(true);
                try {
                    CanvasExecutionResult result = get();
                    status.setText(result.status() + "  /  " + result.duration().toMillis() + " ms");
                    result.nodeTraces().forEach(trace -> nodeStates.put(trace.nodeId(), trace.status().name()));
                    if (result.status() == CanvasExecutionStatus.FAILED) {
                        append("FAILED: " + result.failureMessage());
                    }
                } catch (Exception exception) {
                    status.setText("Run failed");
                    append("ERROR: " + rootMessage(exception));
                }
                graph.repaint();
            }
        }.execute();
    }

    private void acceptEvent(CanvasExecutionEvent event) {
        if (event.nodeId() != null) {
            switch (event.type()) {
                case NODE_STARTED -> nodeStates.put(event.nodeId(), "RUNNING");
                case NODE_OUTPUT_UPDATED -> liveOutputs.put(event.nodeId(), event.outputs());
                case NODE_SUCCEEDED -> nodeStates.put(event.nodeId(), "SUCCEEDED");
                case NODE_FAILED -> nodeStates.put(event.nodeId(), "FAILED");
                case NODE_CANCELLED -> nodeStates.put(event.nodeId(), "CANCELLED");
                default -> { }
            }
        }
        if (event.type() == CanvasExecutionEventType.NODE_CONSOLE_OUTPUT) {
            event.consoleOutput().forEach(this::append);
        } else if (event.type() == CanvasExecutionEventType.NODE_OUTPUT_UPDATED) {
            event.outputs().forEach((portId, value) -> append("value " + shortId(portId) + " = " + value));
        }
        graph.repaint();
    }

    private void append(String line) {
        console.append(line + System.lineSeparator());
        console.setCaretPosition(console.getDocument().getLength());
        if (activeTab != null) {
            activeTab.consoleText = console.getText();
        }
    }

    private void setToolbarEnabled(boolean enabled) {
        java.awt.Container toolbar = (java.awt.Container) frame.getContentPane().getComponent(0);
        setChildrenEnabled(toolbar, enabled);
    }

    private static void setChildrenEnabled(java.awt.Container parent, boolean enabled) {
        for (java.awt.Component child : parent.getComponents()) {
            child.setEnabled(enabled);
            if (child instanceof java.awt.Container container) {
                setChildrenEnabled(container, enabled);
            }
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "Cannot continue", JOptionPane.ERROR_MESSAGE);
        status.setText(message == null ? "Invalid graph" : message);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private final class CanvasTabState {
        private CanvasMvpWorkspace workspace;
        private GraphPanel graph;
        private Path file;
        private long savedRevision;
        private Map<UUID, String> nodeStates;
        private Map<UUID, Map<UUID, Object>> liveOutputs;
        private String consoleText = "";

        private CanvasTabState(CanvasMvpWorkspace workspace, GraphPanel graph, Path file,
                               long savedRevision, Map<UUID, String> nodeStates,
                               Map<UUID, Map<UUID, Object>> liveOutputs) {
            this.workspace = workspace;
            this.graph = graph;
            this.file = file;
            this.savedRevision = savedRevision;
            this.nodeStates = nodeStates;
            this.liveOutputs = liveOutputs;
        }
    }

    private final class GraphPanel extends JPanel {
        private final CanvasMvpWorkspace model;
        private final Map<UUID, NodeCard> cards = new LinkedHashMap<>();
        private UUID pendingNodeId;
        private UUID pendingPortId;

        private GraphPanel() {
            model = workspace;
            setLayout(null);
            setBackground(CANVAS);
            setPreferredSize(new Dimension(900, 650));
            setTransferHandler(new PaletteTransferHandler());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    showConnectionPopup(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    showConnectionPopup(event);
                }
            });
        }

        private void showConnectionPopup(MouseEvent event) {
            if (running || !event.isPopupTrigger()) {
                return;
            }
            CanvasConnection connection = findConnectionAt(event.getPoint());
            if (connection == null) {
                showNodePicker(event.getPoint());
                return;
            }
            JPopupMenu popup = new JPopupMenu();
            javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete connection");
            delete.addActionListener(ignored -> deleteConnection(connection));
            popup.add(delete);
            popup.show(this, event.getX(), event.getY());
        }

        private CanvasConnection findConnectionAt(java.awt.Point point) {
            CanvasConnection nearest = null;
            double nearestDistance = 8.0;
            for (CanvasConnection connection : model.connections()) {
                NodeCard from = cards.get(connection.fromNodeId());
                NodeCard to = cards.get(connection.toNodeId());
                if (from == null || to == null) {
                    continue;
                }
                java.awt.Point start = from.portCenter(connection.fromPortId());
                java.awt.Point end = to.portCenter(connection.toPortId());
                int bend = Math.max(48, Math.abs(end.x - start.x) / 2);
                double distance = Math.min(
                        Line2D.ptSegDist(start.x, start.y, start.x + bend, start.y, point.x, point.y),
                        Math.min(
                                Line2D.ptSegDist(start.x + bend, start.y, start.x + bend, end.y,
                                        point.x, point.y),
                                Line2D.ptSegDist(start.x + bend, end.y, end.x, end.y,
                                        point.x, point.y)));
                if (distance <= nearestDistance) {
                    nearest = connection;
                    nearestDistance = distance;
                }
            }
            return nearest;
        }

        private void addNode(CanvasMvpWorkspace.PlacedNode placed) {
            NodeCard card = new NodeCard(placed.node());
            card.setBounds(placed.x(), placed.y(), 226, card.preferredHeight());
            cards.put(placed.node().nodeId(), card);
            add(card);
        }

        private void reload(java.util.List<CanvasMvpWorkspace.PlacedNode> placedNodes) {
            removeAll();
            cards.clear();
            placedNodes.forEach(this::addNode);
            revalidate();
            repaint();
        }

        private void addConnection(CanvasConnection connection) {
            repaint();
        }

        private void setPendingPort(UUID nodeId, UUID portId) {
            pendingNodeId = nodeId;
            pendingPortId = portId;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(225, 230, 234));
            for (int x = 16; x < getWidth(); x += 24) {
                for (int y = 16; y < getHeight(); y += 24) {
                    g.fillOval(x, y, 2, 2);
                }
            }
            if (model.nodes().isEmpty()) {
                String hint = "右键画布或点击“添加节点”开始";
                g.setColor(new Color(105, 125, 140));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
                java.awt.FontMetrics metrics = g.getFontMetrics();
                g.drawString(hint, Math.max(12, (getWidth() - metrics.stringWidth(hint)) / 2),
                        getHeight() / 2);
            }
            for (CanvasConnection connection : model.connections()) {
                NodeCard from = cards.get(connection.fromNodeId());
                NodeCard to = cards.get(connection.toNodeId());
                if (from == null || to == null) {
                    continue;
                }
                java.awt.Point start = from.portCenter(connection.fromPortId());
                java.awt.Point end = to.portCenter(connection.toPortId());
                int bend = Math.max(48, Math.abs(end.x - start.x) / 2);
                g.setColor(new Color(0, 126, 121));
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(start.x, start.y, start.x + bend, start.y);
                g.drawLine(start.x + bend, start.y, start.x + bend, end.y);
                g.drawLine(start.x + bend, end.y, end.x, end.y);
            }
            if (pendingNodeId != null && pendingPortId != null) {
                NodeCard card = cards.get(pendingNodeId);
                if (card != null) {
                    java.awt.Point start = card.portCenter(pendingPortId);
                    java.awt.Point end = getMousePosition();
                    if (end != null) {
                        g.setColor(TEAL);
                        g.setStroke(new BasicStroke(2f));
                        g.drawLine(start.x, start.y, end.x, end.y);
                    }
                }
            }
            g.dispose();
        }

        private final class NodeCard extends JPanel {
            private final CanvasNode node;
            private final Map<UUID, JButton> portButtons = new HashMap<>();
            private final JLabel stateLabel = new JLabel();
            private int dragX;
            private int dragY;

            private NodeCard(CanvasNode node) {
                this.node = node;
                setLayout(new BorderLayout(6, 8));
                setBackground(Color.WHITE);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(214, 221, 226)),
                        BorderFactory.createEmptyBorder(10, 10, 9, 10)));
                JLabel name = new JLabel(node.name());
                name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                name.setForeground(INK);
                add(name, BorderLayout.NORTH);
                JPopupMenu popup = nodePopup(node);
                setComponentPopupMenu(popup);
                name.setComponentPopupMenu(popup);
                JPanel ports = new JPanel(new GridBagLayout());
                ports.setOpaque(false);
                int row = 0;
                for (CanvasPort port : node.ports()) {
                    JButton label = new JButton((port.direction() == CanvasPortDirection.INPUT ? "o  " : "  ")
                            + port.name() + (port.direction() == CanvasPortDirection.OUTPUT ? "  o" : ""));
                    label.setHorizontalAlignment(port.direction() == CanvasPortDirection.INPUT
                            ? JButton.LEFT : JButton.RIGHT);
                    label.setForeground(port.direction() == CanvasPortDirection.INPUT
                            ? new Color(42, 107, 158) : TEAL);
                    label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                    label.setBorderPainted(false);
                    label.setContentAreaFilled(false);
                    label.setFocusPainted(false);
                    label.setToolTipText(port.direction() == CanvasPortDirection.INPUT
                            ? "Click after selecting an output" : "Click to start a connection");
                    label.addActionListener(event -> selectPort(node, port));
                    label.setComponentPopupMenu(popup);
                    portButtons.put(port.portId(), label);
                    GridBagConstraints c = new GridBagConstraints();
                    c.gridx = port.direction() == CanvasPortDirection.INPUT ? 0 : 1;
                    c.gridy = row++;
                    c.anchor = port.direction() == CanvasPortDirection.INPUT
                            ? GridBagConstraints.WEST : GridBagConstraints.EAST;
                    c.weightx = 1;
                    c.fill = GridBagConstraints.HORIZONTAL;
                    ports.add(label, c);
                }
                JPanel body = new JPanel(new BorderLayout(0, 4));
                body.setOpaque(false);
                if (node.nodeType().equals(CanvasMvpWorkspace.constantNodeType())
                        && node.configuration().containsKey("value")) {
                    JLabel value = new JLabel("value: " + node.configuration().get("value"));
                    value.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                    value.setForeground(MUTED);
                    value.setComponentPopupMenu(popup);
                    body.add(value, BorderLayout.NORTH);
                }
                body.add(ports, BorderLayout.CENTER);
                ports.setComponentPopupMenu(popup);
                add(body, BorderLayout.CENTER);
                stateLabel.setForeground(MUTED);
                stateLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                stateLabel.setComponentPopupMenu(popup);
                add(stateLabel, BorderLayout.SOUTH);
                MouseAdapter drag = new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent event) {
                        dragX = event.getX();
                        dragY = event.getY();
                    }

                    @Override
                    public void mouseDragged(MouseEvent event) {
                        if (running) {
                            return;
                        }
                        int x = getX() + event.getX() - dragX;
                        int y = getY() + event.getY() - dragY;
                        setLocation(Math.max(0, x), Math.max(0, y));
                        model.moveNode(node.nodeId(), getX(), getY());
                        updateDocumentTitle();
                        repaint();
                    }
                };
                addMouseListener(drag);
                addMouseMotionListener(drag);
                name.addMouseListener(drag);
                name.addMouseMotionListener(drag);
                updateState();
            }

            private int preferredHeight() {
                int configurationHeight = node.nodeType().equals(CanvasMvpWorkspace.constantNodeType())
                        && node.configuration().containsKey("value") ? 20 : 0;
                return Math.max(100, 68 + node.ports().size() * 23 + configurationHeight);
            }

            private JPopupMenu nodePopup(CanvasNode target) {
                JPopupMenu popup = new JPopupMenu();
                if (target.nodeType().equals(CanvasMvpWorkspace.constantNodeType())) {
                    javax.swing.JMenuItem edit = new javax.swing.JMenuItem("Edit value");
                    edit.addActionListener(event -> editConstantNode(target));
                    popup.add(edit);
                    popup.addSeparator();
                }
                javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete node");
                delete.addActionListener(event -> deleteNode(target));
                popup.add(delete);
                return popup;
            }

            private java.awt.Point portCenter(UUID portId) {
                JButton button = portButtons.get(portId);
                java.awt.Point point = SwingUtilities.convertPoint(button,
                        button.getWidth() / 2, button.getHeight() / 2, this);
                return SwingUtilities.convertPoint(this, point, GraphPanel.this);
            }

            private void updateState() {
                String state = nodeStates.getOrDefault(node.nodeId(), "READY");
                Map<UUID, Object> values = liveOutputs.get(node.nodeId());
                stateLabel.setText(values == null || values.isEmpty()
                        ? state : state + "  |  " + values.values().stream().map(String::valueOf).toList());
                stateLabel.setForeground("RUNNING".equals(state) ? TEAL : MUTED);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                updateState();
                super.paintComponent(graphics);
                if (pendingNodeId != null && pendingPortId != null
                        && pendingNodeId.equals(node.nodeId())) {
                    JButton pending = portButtons.get(pendingPortId);
                    if (pending != null) {
                        pending.setForeground(new Color(197, 92, 31));
                    }
                } else {
                    node.ports().forEach(port -> portButtons.get(port.portId()).setForeground(
                            port.direction() == CanvasPortDirection.INPUT
                                    ? new Color(42, 107, 158) : TEAL));
                }
            }
        }
    }
}
