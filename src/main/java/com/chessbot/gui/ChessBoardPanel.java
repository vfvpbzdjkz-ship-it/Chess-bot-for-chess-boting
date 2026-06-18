package com.chessbot.gui;

import com.chessbot.engine.Board;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class ChessBoardPanel extends JPanel {
    private static final Color LIGHT  = new Color(240, 217, 181);
    private static final Color DARK   = new Color(181, 136,  99);
    private static final Color HILITE = new Color( 50, 205,  50, 120);

    private Board board;
    private int selectedSquare = -1;
    private boolean flipped = false; // true = black's perspective

    // Unicode chess pieces
    private static final String[] WHITE_PIECES = {"", "♙", "♘", "♗", "♖", "♕", "♔"};
    private static final String[] BLACK_PIECES = {"", "♟", "♞", "♝", "♜", "♛", "♚"};

    public ChessBoardPanel() {
        board = Board.startPosition();
        setPreferredSize(new Dimension(480, 480));
        setMinimumSize(new Dimension(320, 320));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int sq = squareFromPoint(e.getX(), e.getY());
                if (sq >= 0) {
                    selectedSquare = (selectedSquare == sq) ? -1 : sq;
                    repaint();
                }
            }
        });
    }

    public void setBoard(Board b) {
        this.board = b;
        selectedSquare = -1;
        SwingUtilities.invokeLater(this::repaint);
    }

    public void setBoardFromGrid(String[][] grid) {
        // grid[rank][file], rank 0=rank1, rank 7=rank8
        Board b = new Board();
        b.squares = new int[64];
        b.whiteToMove = true;
        b.epSquare = Board.NO_EP;
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                String s = grid[rank][file];
                if (s == null || s.equals(".")) continue;
                int sq = rank * 8 + file;
                b.squares[sq] = parseSymbol(s);
            }
        }
        setBoard(b);
    }

    private static int parseSymbol(String s) {
        if (s == null || s.isBlank()) return Board.EMPTY;
        boolean white = Character.isUpperCase(s.charAt(0));
        int type = switch (s.toLowerCase()) {
            case "p" -> Board.PAWN;   case "n" -> Board.KNIGHT;
            case "b" -> Board.BISHOP; case "r" -> Board.ROOK;
            case "q" -> Board.QUEEN;  case "k" -> Board.KING;
            default  -> Board.EMPTY;
        };
        return Board.makePiece(type, white ? Board.WHITE : Board.BLACK);
    }

    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
        repaint();
    }

    public void flip() {
        flipped = !flipped;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth(), h = getHeight();
        int sq  = Math.min(w, h) / 8;
        int offsetX = (w - sq * 8) / 2;
        int offsetY = (h - sq * 8) / 2;
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                int displayRank = flipped ? rank : 7 - rank;
                int displayFile = flipped ? 7 - file : file;
                int x = offsetX + file * sq;
                int y = offsetY + rank * sq;

                boolean isLight = (displayRank + displayFile) % 2 == 0;
                g2.setColor(isLight ? LIGHT : DARK);
                g2.fillRect(x, y, sq, sq);

                int boardSq = displayRank * 8 + displayFile;
                if (boardSq == selectedSquare) {
                    g2.setColor(HILITE);
                    g2.fillRect(x, y, sq, sq);
                }

                // Draw piece
                if (board != null) {
                    int piece = board.squares[boardSq];
                    if (piece != Board.EMPTY) {
                        int type  = Board.pieceType(piece);
                        int color = Board.pieceColor(piece);
                        String symbol = color == Board.WHITE ? WHITE_PIECES[type] : BLACK_PIECES[type];
                        Font font = new Font("Serif", Font.PLAIN, (int)(sq * 0.78));
                        g2.setFont(font);
                        FontMetrics fm = g2.getFontMetrics();
                        int tx = x + (sq - fm.stringWidth(symbol)) / 2;
                        int ty = y + (sq - fm.getHeight()) / 2 + fm.getAscent();
                        // Shadow
                        g2.setColor(new Color(0,0,0,60));
                        g2.drawString(symbol, tx+1, ty+1);
                        g2.setColor(color == Board.WHITE ? new Color(255,255,255) : new Color(20,20,20));
                        g2.drawString(symbol, tx, ty);
                    }
                }
            }
        }

        // Rank/file labels
        g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(9, sq / 5)));
        for (int i = 0; i < 8; i++) {
            // Rank numbers
            int rank = flipped ? i + 1 : 8 - i;
            int y = offsetY + i * sq + sq / 2;
            g2.setColor(((8 - rank) % 2 == 0) ? DARK : LIGHT);
            g2.drawString(String.valueOf(rank), offsetX + 2, y);
            // File letters
            char file = (char)('a' + (flipped ? 7 - i : i));
            int x = offsetX + i * sq + sq / 2;
            g2.setColor(((i + (8 - i)) % 2 == 0) ? DARK : LIGHT);
            g2.drawString(String.valueOf(file), x, offsetY + 8 * sq - 2);
        }
    }

    private int squareFromPoint(int px, int py) {
        int w = getWidth(), h = getHeight();
        int sq  = Math.min(w, h) / 8;
        int offsetX = (w - sq * 8) / 2;
        int offsetY = (h - sq * 8) / 2;
        int file = (px - offsetX) / sq;
        int rank = (py - offsetY) / sq;
        if (file < 0 || file > 7 || rank < 0 || rank > 7) return -1;
        int displayFile = flipped ? 7 - file : file;
        int displayRank = flipped ? rank : 7 - rank;
        return displayRank * 8 + displayFile;
    }
}
