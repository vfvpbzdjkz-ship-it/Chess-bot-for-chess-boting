package com.chessbot.gui;

import com.chessbot.engine.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

public class MainWindow extends JFrame {
    private final ChessBoardPanel boardPanel;
    private final LichessPanel    lichessPanel;
    private final JLabel          statusBar;
    private final JButton         flipBtn;
    private final JButton         newLocalGameBtn;

    // Local play state
    private Board localBoard;
    private int   selectedSq = -1;
    private Search localSearch;
    private boolean localBotEnabled = true;
    private boolean localBotIsBlack = true; // bot plays black by default
    private Thread  botThread;

    public MainWindow() {
        super("Chess Bot — Lichess Edition");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(5, 5));
        getRootPane().setBorder(new EmptyBorder(6, 6, 6, 6));

        boardPanel  = new ChessBoardPanel();
        lichessPanel = new LichessPanel();
        statusBar   = new JLabel("Welcome! Connect to Lichess or play a local game.");
        statusBar.setBorder(new EmptyBorder(3, 6, 3, 6));
        statusBar.setFont(statusBar.getFont().deriveFont(12f));

        flipBtn = new JButton("Flip Board");
        flipBtn.addActionListener(e -> boardPanel.flip());

        newLocalGameBtn = new JButton("New Local Game");
        newLocalGameBtn.addActionListener(e -> startLocalGame());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        toolbar.add(newLocalGameBtn);
        toolbar.add(flipBtn);
        JLabel localLabel = new JLabel("  (local game: you=white, bot=black)");
        localLabel.setForeground(Color.GRAY);
        toolbar.add(localLabel);

        // Wire lichess panel to update board
        lichessPanel.setOnBoardUpdate(grid ->
            SwingUtilities.invokeLater(() -> boardPanel.setBoardFromGrid(grid)));
        lichessPanel.setOnColorKnown(asWhite ->
            SwingUtilities.invokeLater(() -> boardPanel.setFlipped(!asWhite)));

        add(toolbar,      BorderLayout.NORTH);
        add(boardPanel,   BorderLayout.CENTER);
        add(lichessPanel, BorderLayout.EAST);
        add(statusBar,    BorderLayout.SOUTH);

        // Local game mouse handler
        boardPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleLocalClick(e.getX(), e.getY());
            }
        });

        pack();
        setMinimumSize(new Dimension(760, 560));
        setLocationRelativeTo(null);

        startLocalGame();
    }

    // ---- Local game ----

    private void startLocalGame() {
        if (botThread != null) botThread.interrupt();
        localBoard = Board.startPosition();
        localSearch = new Search();
        selectedSq = -1;
        localBotIsBlack = true;
        boardPanel.setFlipped(false);
        boardPanel.setBoard(localBoard.copy());
        setStatus("Local game started. You play White.");
    }

    private void handleLocalClick(int px, int py) {
        if (localBoard == null) return;
        // Is it human's turn?
        boolean humanTurn = localBotIsBlack == !localBoard.whiteToMove;
        if (!humanTurn) return;

        int sq = screenToSquare(px, py);
        if (sq < 0) return;

        if (selectedSq < 0) {
            int piece = localBoard.squares[sq];
            if (piece == Board.EMPTY) return;
            int color = Board.pieceColor(piece);
            boolean myColor = localBotIsBlack ? color == Board.WHITE : color == Board.BLACK;
            if (!myColor) return;
            selectedSq = sq;
            boardPanel.setBoard(localBoard.copy()); // triggers repaint
        } else {
            // Try to make a move from selectedSq -> sq
            int from = selectedSq;
            selectedSq = -1;
            boolean moved = tryLocalMove(from, sq);
            boardPanel.setBoard(localBoard.copy());
            if (moved) triggerBotMove();
        }
    }

    private boolean tryLocalMove(int from, int to) {
        var moves = MoveGenerator.generateLegalMoves(localBoard);
        // If promotion, pick queen automatically
        Move chosen = null;
        for (Move m : moves) {
            if (m.from == from && m.to == to) {
                if (m.isPromotion()) {
                    if (m.promoted == Board.QUEEN) { chosen = m; break; }
                } else {
                    chosen = m; break;
                }
            }
        }
        if (chosen == null) {
            setStatus("Illegal move.");
            return false;
        }
        localBoard.makeMove(chosen);
        setStatus("You played " + chosen.toUCI());
        checkLocalGameOver();
        return true;
    }

    private void triggerBotMove() {
        if (localBoard == null) return;
        var legalMoves = MoveGenerator.generateLegalMoves(localBoard);
        if (legalMoves.isEmpty()) return;

        setStatus("Bot is thinking...");
        boardPanel.setEnabled(false);

        botThread = new Thread(() -> {
            Move best = localSearch.findBestMove(localBoard, 5000);
            SwingUtilities.invokeLater(() -> {
                boardPanel.setEnabled(true);
                if (best != null && localBoard != null) {
                    localBoard.makeMove(best);
                    boardPanel.setBoard(localBoard.copy());
                    setStatus("Bot played " + best.toUCI());
                    checkLocalGameOver();
                }
            });
        }, "local-bot");
        botThread.setDaemon(true);
        botThread.start();
    }

    private void checkLocalGameOver() {
        if (localBoard == null) return;
        var moves = MoveGenerator.generateLegalMoves(localBoard);
        if (moves.isEmpty()) {
            int color = localBoard.whiteToMove ? Board.WHITE : Board.BLACK;
            if (localBoard.isInCheck(color)) {
                String winner = localBoard.whiteToMove ? "Black" : "White";
                setStatus("Checkmate! " + winner + " wins.");
            } else {
                setStatus("Stalemate — draw!");
            }
            localBoard = null;
        }
    }

    private int screenToSquare(int px, int py) {
        int w = boardPanel.getWidth(), h = boardPanel.getHeight();
        int sq = Math.min(w, h) / 8;
        int offX = (w - sq * 8) / 2, offY = (h - sq * 8) / 2;
        int file = (px - offX) / sq;
        int rank = (py - offY) / sq;
        if (file < 0 || file > 7 || rank < 0 || rank > 7) return -1;
        int displayFile = file, displayRank = 7 - rank;
        return displayRank * 8 + displayFile;
    }

    private void setStatus(String msg) {
        statusBar.setText(msg);
    }
}
