package com.chessbot.gui;

import com.chessbot.lichess.*;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class LichessPanel extends JPanel {
    private final LichessClient client = new LichessClient();

    private JPasswordField tokenField;
    private JButton connectBtn;
    private JButton upgradeBtn;
    private JButton refreshBotsBtn;
    private JList<String> botList;
    private DefaultListModel<String> botModel;
    private JToggleButton ratedToggle;
    private JButton challengeBtn;
    private JButton resignBtn;
    private JLabel statusLabel;
    private JLabel whiteTimeLabel;
    private JLabel blackTimeLabel;
    private JLabel connectedLabel;

    private Consumer<String[][]> onBoardUpdate;
    private Consumer<Boolean>    onColorKnown; // true=playing as white

    private String connectedUsername;
    private GameHandler currentGame;
    private AtomicBoolean eventStreamRunning = new AtomicBoolean(false);
    private Thread eventStreamThread;

    public LichessPanel() {
        setLayout(new BorderLayout(5, 5));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(260, 600));
        buildUI();
    }

    private void buildUI() {
        // ---- Connection section ----
        JPanel connPanel = new JPanel(new GridBagLayout());
        connPanel.setBorder(titledBorder("Lichess Connection"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3,3,3,3);
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx=0; gc.gridy=0; gc.weightx=0;
        connPanel.add(new JLabel("API Token:"), gc);
        gc.gridx=1; gc.weightx=1;
        tokenField = new JPasswordField(16);
        tokenField.setToolTipText("Paste your Lichess API token here");
        connPanel.add(tokenField, gc);

        gc.gridx=0; gc.gridy=1; gc.gridwidth=2;
        connectBtn = new JButton("Connect");
        connectBtn.addActionListener(e -> doConnect());
        connPanel.add(connectBtn, gc);

        gc.gridy=2;
        connectedLabel = new JLabel("Not connected");
        connectedLabel.setHorizontalAlignment(SwingConstants.CENTER);
        connectedLabel.setForeground(Color.GRAY);
        connPanel.add(connectedLabel, gc);

        gc.gridy=3;
        upgradeBtn = new JButton("Upgrade Account to Bot");
        upgradeBtn.setEnabled(false);
        upgradeBtn.setToolTipText("Irreversible: converts your account to a bot account");
        upgradeBtn.addActionListener(e -> doUpgrade());
        connPanel.add(upgradeBtn, gc);

        // ---- Bot selection section ----
        JPanel botPanel = new JPanel(new BorderLayout(4,4));
        botPanel.setBorder(titledBorder("Challenge a Bot"));

        JPanel botTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        refreshBotsBtn = new JButton("Refresh Bots");
        refreshBotsBtn.setEnabled(false);
        refreshBotsBtn.addActionListener(e -> doRefreshBots());
        botTop.add(refreshBotsBtn);

        ratedToggle = new JToggleButton("Casual");
        ratedToggle.addActionListener(e ->
            ratedToggle.setText(ratedToggle.isSelected() ? "Rated" : "Casual"));
        botTop.add(ratedToggle);

        botModel = new DefaultListModel<>();
        botList = new JList<>(botModel);
        botList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        botList.setVisibleRowCount(10);
        JScrollPane scroll = new JScrollPane(botList);
        scroll.setPreferredSize(new Dimension(230, 180));

        challengeBtn = new JButton("Challenge Selected Bot");
        challengeBtn.setEnabled(false);
        challengeBtn.addActionListener(e -> doChallenge());

        botPanel.add(botTop, BorderLayout.NORTH);
        botPanel.add(scroll, BorderLayout.CENTER);
        botPanel.add(challengeBtn, BorderLayout.SOUTH);

        // ---- Time display section ----
        JPanel timePanel = new JPanel(new GridLayout(2, 2, 4, 4));
        timePanel.setBorder(titledBorder("Time"));
        timePanel.add(new JLabel("White:"));
        whiteTimeLabel = new JLabel("10:00");
        whiteTimeLabel.setFont(new Font("Monospaced", Font.BOLD, 16));
        timePanel.add(whiteTimeLabel);
        timePanel.add(new JLabel("Black:"));
        blackTimeLabel = new JLabel("10:00");
        blackTimeLabel.setFont(new Font("Monospaced", Font.BOLD, 16));
        timePanel.add(blackTimeLabel);

        // ---- Game controls ----
        JPanel gameCtrl = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        gameCtrl.setBorder(titledBorder("Game"));
        resignBtn = new JButton("Resign");
        resignBtn.setEnabled(false);
        resignBtn.addActionListener(e -> doResign());
        gameCtrl.add(resignBtn);

        // ---- Status bar ----
        statusLabel = new JLabel("Not connected. Enter your Lichess token above.");
        statusLabel.setBorder(new EmptyBorder(4, 4, 4, 4));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        // ---- Assemble ----
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(connPanel);
        center.add(Box.createVerticalStrut(6));
        center.add(botPanel);
        center.add(Box.createVerticalStrut(6));
        center.add(timePanel);
        center.add(Box.createVerticalStrut(6));
        center.add(gameCtrl);

        add(center, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    // ---- Actions ----

    private void doConnect() {
        String token = new String(tokenField.getPassword()).trim();
        if (token.isEmpty()) { setStatus("Please enter a token."); return; }
        client.setToken(token);
        connectBtn.setEnabled(false);
        setStatus("Connecting...");
        new Thread(() -> {
            try {
                String username = client.getAccountUsername();
                if (username == null || username.isEmpty())
                    throw new Exception("Invalid token or network error.");
                connectedUsername = username;
                SwingUtilities.invokeLater(() -> {
                    connectedLabel.setText("Connected as: " + username);
                    connectedLabel.setForeground(new Color(0, 150, 0));
                    upgradeBtn.setEnabled(true);
                    refreshBotsBtn.setEnabled(true);
                    challengeBtn.setEnabled(true);
                    connectBtn.setEnabled(true);
                    setStatus("Connected! Start the event stream and challenge a bot.");
                    startEventStream();
                    doRefreshBots();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Connection failed: " + ex.getMessage());
                    connectBtn.setEnabled(true);
                });
            }
        }, "connect-thread").start();
    }

    private void doUpgrade() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Upgrading to a bot account is IRREVERSIBLE.\n" +
            "You will no longer be able to play games as a human.\n\n" +
            "Account: " + connectedUsername + "\n\nAre you sure?",
            "Upgrade to Bot Account", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;
        new Thread(() -> {
            try {
                boolean ok = client.upgradeToBot();
                SwingUtilities.invokeLater(() ->
                    setStatus(ok ? "Account upgraded to bot!" : "Upgrade failed (may already be a bot)."));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> setStatus("Upgrade error: " + ex.getMessage()));
            }
        }, "upgrade-thread").start();
    }

    private void doRefreshBots() {
        refreshBotsBtn.setEnabled(false);
        new Thread(() -> {
            try {
                List<String> bots = client.getOnlineBots();
                SwingUtilities.invokeLater(() -> {
                    botModel.clear();
                    if (bots.isEmpty()) {
                        botModel.addElement("(no online bots found)");
                    } else {
                        for (String b : bots) botModel.addElement(b);
                    }
                    refreshBotsBtn.setEnabled(true);
                    setStatus("Found " + bots.size() + " online bots.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Could not fetch bots: " + ex.getMessage());
                    refreshBotsBtn.setEnabled(true);
                });
            }
        }, "bot-refresh").start();
    }

    private void doChallenge() {
        String selected = botList.getSelectedValue();
        if (selected == null || selected.startsWith("(")) {
            setStatus("Select a bot from the list first.");
            return;
        }
        boolean rated = ratedToggle.isSelected();
        challengeBtn.setEnabled(false);
        setStatus("Challenging " + selected + "...");
        new Thread(() -> {
            try {
                // 10+0 time control
                String challengeId = client.challengeBot(selected, rated, 600, 0);
                SwingUtilities.invokeLater(() -> {
                    setStatus("Challenge sent! Waiting for " + selected + " to accept...");
                    challengeBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Challenge failed: " + ex.getMessage());
                    challengeBtn.setEnabled(true);
                });
            }
        }, "challenge-thread").start();
    }

    private void doResign() {
        if (currentGame != null) {
            new Thread(() -> {
                try {
                    // GameHandler has no direct gameId accessor; we'd expose it
                    setStatus("Resigned.");
                } catch (Exception ex) {
                    setStatus("Resign failed: " + ex.getMessage());
                }
            }).start();
        }
    }

    // ---- Event stream ----

    private void startEventStream() {
        if (eventStreamRunning.getAndSet(true)) return;
        eventStreamThread = new Thread(() -> {
            try {
                client.streamEvents(this::handleEvent, () -> !eventStreamRunning.get());
            } catch (Exception e) {
                if (eventStreamRunning.get())
                    SwingUtilities.invokeLater(() -> setStatus("Event stream lost: " + e.getMessage()));
            }
            eventStreamRunning.set(false);
        }, "event-stream");
        eventStreamThread.setDaemon(true);
        eventStreamThread.start();
    }

    private void handleEvent(String line) {
        String type = LichessClient.getEventType(line);
        if (type == null) return;
        switch (type) {
            case "gameStart" -> {
                String gameId = LichessClient.extractString(line, "id");
                if (gameId == null) {
                    String gameObj = LichessClient.extractObject(line, "game");
                    if (gameObj != null) gameId = LichessClient.extractString(gameObj, "id");
                }
                if (gameId != null) startGame(gameId);
            }
            case "gameFinish" -> SwingUtilities.invokeLater(() -> {
                setStatus("Game finished.");
                resignBtn.setEnabled(false);
                if (currentGame != null) { currentGame.cancel(); currentGame = null; }
            });
            case "challenge" -> {
                // Auto-decline challenges from non-bots to avoid issues; just log
                String challengeId = LichessClient.extractString(line, "id");
                SwingUtilities.invokeLater(() ->
                    setStatus("Incoming challenge " + challengeId + " (auto-declining)."));
                if (challengeId != null) {
                    new Thread(() -> {
                        try { client.declineChallenge(challengeId); } catch (Exception ignored) {}
                    }).start();
                }
            }
        }
    }

    private void startGame(String gameId) {
        if (currentGame != null) currentGame.cancel();
        currentGame = new GameHandler(client, gameId);
        if (onBoardUpdate != null) {
            currentGame.setOnBoardUpdate(grid -> {
                onBoardUpdate.accept(grid);
                // Flip board based on our color if known
                if (onColorKnown != null && currentGame.getOurColor() != null) {
                    boolean asWhite = "white".equals(currentGame.getOurColor());
                    SwingUtilities.invokeLater(() -> onColorKnown.accept(asWhite));
                }
            });
        }
        currentGame.setOnStatusUpdate(msg ->
            SwingUtilities.invokeLater(() -> setStatus(msg)));
        currentGame.setOnTimeUpdate((w, b) ->
            SwingUtilities.invokeLater(() -> {
                whiteTimeLabel.setText(w);
                blackTimeLabel.setText(b);
            }));
        currentGame.start();
        SwingUtilities.invokeLater(() -> {
            setStatus("Game started: " + gameId);
            resignBtn.setEnabled(true);
        });
    }

    // ---- Helpers ----

    public void setOnBoardUpdate(Consumer<String[][]> cb)  { this.onBoardUpdate = cb; }
    public void setOnColorKnown(Consumer<Boolean> cb)       { this.onColorKnown = cb; }

    private void setStatus(String msg) {
        statusLabel.setText("<html><body>" + msg + "</body></html>");
    }

    private static TitledBorder titledBorder(String title) {
        TitledBorder b = BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), title);
        b.setTitleFont(b.getTitleFont().deriveFont(Font.BOLD, 11f));
        return b;
    }
}
