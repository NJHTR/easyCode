package com.easycode.canvas.desktop;

import com.easycode.canvas.execution.CanvasExecutionEvent;
import com.easycode.canvas.execution.CanvasExecutionEventType;
import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
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
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Standalone Swing entry point for the first visual graph execution MVP. */
public final class CanvasDesktopApplication {
    private static final Color INK = new Color(39, 48, 59);
    private static final Color MUTED = new Color(106, 118, 132);
    private static final Color CANVAS = new Color(245, 247, 249);
    private static final Color TEAL = new Color(0, 126, 121);

    private final CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
    private final CanvasDocumentStore documentStore = new CanvasDocumentStore();
    private final Map<UUID, String> nodeStates = new HashMap<>();
    private final Map<UUID, Map<UUID, Object>> liveOutputs = new HashMap<>();
    private final JTextArea console = new JTextArea();
    private final JLabel status = new JLabel("Ready");
    private final GraphPanel graph = new GraphPanel();
    private JFrame frame;
    private UUID pendingOutputNode;
    private String pendingOutputName;
    private boolean running;
    private Path currentFile;
    private long savedRevision;

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
                if (confirmUnsavedChanges()) {
                    frame.dispose();
                }
            }
        });
        frame.setMinimumSize(new Dimension(960, 650));
        frame.setSize(1180, 760);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.add(toolbar(), BorderLayout.NORTH);
        JScrollPane canvasScroll = new JScrollPane(graph);
        canvasScroll.setBorder(BorderFactory.createEmptyBorder());
        frame.add(canvasScroll, BorderLayout.CENTER);
        frame.add(inspector(), BorderLayout.EAST);
        frame.setVisible(true);
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        bar.setBackground(Color.WHITE);
        JLabel brand = new JLabel("easyCode  /  Canvas");
        brand.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        brand.setForeground(INK);
        bar.add(brand, BorderLayout.WEST);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        addAction(actions, "Open", this::openCanvas);
        addAction(actions, "Save", () -> saveCanvas());
        addAction(actions, "Constant", () -> addConstant());
        addAction(actions, "Add", () -> addNode(CanvasMvpWorkspace.addNodeType()));
        addAction(actions, "Print", () -> addNode(CanvasMvpWorkspace.printNodeType()));
        addAction(actions, "Run", this::runGraph, true);
        bar.add(actions, BorderLayout.CENTER);
        status.setForeground(MUTED);
        bar.add(status, BorderLayout.EAST);
        return bar;
    }

    private JPanel inspector() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(310, 0));
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

    private void addAction(JPanel actions, String label, Runnable action) {
        addAction(actions, label, action, false);
    }

    private void addAction(JPanel actions, String label, Runnable action, boolean primary) {
        JButton button = new JButton(label);
        button.setFocusPainted(false);
        button.setBackground(primary ? TEAL : new Color(240, 244, 246));
        button.setForeground(primary ? Color.WHITE : INK);
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        button.setEnabled(!running);
        button.addActionListener(event -> action.run());
        actions.add(button);
    }

    private void addConstant() {
        JTextField value = new JTextField("hello easyCode", 18);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 8);
        form.add(new JLabel("Value"), c);
        c.gridx = 1;
        form.add(value, c);
        if (JOptionPane.showConfirmDialog(frame, form, "Add constant node",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String text = value.getText();
        Object parsed = parseValue(text);
        addNode(CanvasMvpWorkspace.constantNodeType(), Map.of("value", parsed));
    }

    private void openCanvas() {
        JFileChooser chooser = canvasFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath();
        try {
            CanvasMvpDocument document = documentStore.load(selected);
            if (!confirmUnsavedChanges()) {
                return;
            }
            workspace.replace(document);
            savedRevision = workspace.revision();
            currentFile = selected;
            nodeStates.clear();
            document.nodes().forEach(node -> nodeStates.put(node.node().nodeId(), "READY"));
            liveOutputs.clear();
            pendingOutputNode = null;
            pendingOutputName = null;
            console.setText("");
            graph.reload(workspace.nodes());
            graph.setPendingPort(null, null);
            updateDocumentTitle();
            status.setText("Opened " + selected.getFileName());
        } catch (IOException | RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private boolean saveCanvas() {
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

    private void updateDocumentTitle() {
        if (frame == null) {
            return;
        }
        String title = "easyCode Canvas MVP - " + workspace.document().name();
        frame.setTitle(workspace.revision() == savedRevision ? title : title + " *");
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

    private static Object parseValue(String text) {
        String value = text == null ? "" : text.trim();
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private void addNode(String type) {
        addNode(type, Map.of());
    }

    private void addNode(String type, Map<String, Object> configuration) {
        try {
            CanvasMvpWorkspace.PlacedNode node = workspace.addNode(type, configuration);
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

    private final class GraphPanel extends JPanel {
        private final Map<UUID, NodeCard> cards = new LinkedHashMap<>();
        private UUID pendingNodeId;
        private UUID pendingPortId;

        private GraphPanel() {
            setLayout(null);
            setBackground(CANVAS);
            setPreferredSize(new Dimension(900, 650));
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
            for (CanvasConnection connection : workspace.connections()) {
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
            for (CanvasConnection connection : workspace.connections()) {
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
                        workspace.moveNode(node.nodeId(), getX(), getY());
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
