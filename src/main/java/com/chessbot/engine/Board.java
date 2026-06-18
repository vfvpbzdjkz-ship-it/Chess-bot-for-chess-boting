package com.chessbot.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Chess board using 8x8 mailbox representation.
 * Piece encoding: type | (color << 3)
 * Types: EMPTY=0, PAWN=1, KNIGHT=2, BISHOP=3, ROOK=4, QUEEN=5, KING=6
 * Colors: WHITE=0, BLACK=1
 */
public class Board {
    public static final int EMPTY  = 0;
    public static final int PAWN   = 1;
    public static final int KNIGHT = 2;
    public static final int BISHOP = 3;
    public static final int ROOK   = 4;
    public static final int QUEEN  = 5;
    public static final int KING   = 6;

    public static final int WHITE = 0;
    public static final int BLACK = 1;

    public static final int NO_EP = -1;

    public int[] squares = new int[64];
    public boolean whiteToMove;
    public boolean[] castling = new boolean[4]; // WK, WQ, BK, BQ
    public int epSquare;
    public int halfMoveClock;
    public int fullMoveNumber;
    public long zobristHash;

    private static final long[][] ZOBRIST_PIECES = new long[15][64];
    private static final long ZOBRIST_SIDE;
    private static final long[] ZOBRIST_CASTLING = new long[4];
    private static final long[] ZOBRIST_EP = new long[8];

    static {
        java.util.Random rng = new java.util.Random(0xDEADBEEFL);
        for (int p = 0; p < 15; p++)
            for (int sq = 0; sq < 64; sq++)
                ZOBRIST_PIECES[p][sq] = rng.nextLong();
        ZOBRIST_SIDE = rng.nextLong();
        for (int i = 0; i < 4; i++) ZOBRIST_CASTLING[i] = rng.nextLong();
        for (int i = 0; i < 8; i++) ZOBRIST_EP[i] = rng.nextLong();
    }

    private static class UndoInfo {
        int capturedPiece;
        boolean[] castling;
        int epSquare;
        int halfMoveClock;
        long zobristHash;
    }

    private final Deque<UndoInfo> history = new ArrayDeque<>();

    public static int pieceType(int p)  { return p & 7; }
    public static int pieceColor(int p) { return p >> 3; }
    public static int makePiece(int type, int color) { return type | (color << 3); }

    public Board() {
        epSquare = NO_EP;
        halfMoveClock = 0;
        fullMoveNumber = 1;
    }

    public void loadFEN(String fen) {
        String[] parts = fen.trim().split("\\s+");
        // board
        int rank = 7, file = 0;
        squares = new int[64];
        for (char c : parts[0].toCharArray()) {
            if (c == '/') { rank--; file = 0; }
            else if (Character.isDigit(c)) { file += c - '0'; }
            else {
                int color = Character.isUpperCase(c) ? WHITE : BLACK;
                int type = switch (Character.toLowerCase(c)) {
                    case 'p' -> PAWN; case 'n' -> KNIGHT; case 'b' -> BISHOP;
                    case 'r' -> ROOK; case 'q' -> QUEEN; case 'k' -> KING;
                    default -> EMPTY;
                };
                squares[rank * 8 + file] = makePiece(type, color);
                file++;
            }
        }
        whiteToMove = parts.length < 2 || parts[1].equals("w");
        castling = new boolean[4];
        if (parts.length >= 3) {
            String c = parts[2];
            castling[0] = c.contains("K");
            castling[1] = c.contains("Q");
            castling[2] = c.contains("k");
            castling[3] = c.contains("q");
        }
        epSquare = (parts.length >= 4 && !parts[3].equals("-"))
            ? Move.squareIndex(parts[3]) : NO_EP;
        halfMoveClock  = parts.length >= 5 ? Integer.parseInt(parts[4]) : 0;
        fullMoveNumber = parts.length >= 6 ? Integer.parseInt(parts[5]) : 1;
        rebuildHash();
        history.clear();
    }

    private void rebuildHash() {
        zobristHash = 0;
        for (int sq = 0; sq < 64; sq++)
            if (squares[sq] != EMPTY) zobristHash ^= ZOBRIST_PIECES[squares[sq]][sq];
        if (!whiteToMove) zobristHash ^= ZOBRIST_SIDE;
        for (int i = 0; i < 4; i++) if (castling[i]) zobristHash ^= ZOBRIST_CASTLING[i];
        if (epSquare != NO_EP) zobristHash ^= ZOBRIST_EP[epSquare % 8];
    }

    public void makeMove(Move m) {
        UndoInfo u = new UndoInfo();
        u.capturedPiece = m.captured;
        u.castling = castling.clone();
        u.epSquare = epSquare;
        u.halfMoveClock = halfMoveClock;
        u.zobristHash = zobristHash;
        history.push(u);

        int from = m.from, to = m.to;
        int piece = m.piece;
        int type = pieceType(piece);
        int color = pieceColor(piece);
        int opp = color ^ 1;

        // hash out old state
        zobristHash ^= ZOBRIST_PIECES[piece][from];
        if (epSquare != NO_EP) zobristHash ^= ZOBRIST_EP[epSquare % 8];
        for (int i = 0; i < 4; i++) if (castling[i]) zobristHash ^= ZOBRIST_CASTLING[i];

        // update half-move clock
        if (type == PAWN || m.captured != EMPTY) halfMoveClock = 0;
        else halfMoveClock++;

        // clear from
        squares[from] = EMPTY;
        // remove captured
        if (m.flags == Move.FLAG_EP) {
            int capSq = to + (color == WHITE ? -8 : 8);
            zobristHash ^= ZOBRIST_PIECES[squares[capSq]][capSq];
            squares[capSq] = EMPTY;
        } else if (m.captured != EMPTY) {
            zobristHash ^= ZOBRIST_PIECES[m.captured][to];
        }

        // place piece
        int placedPiece = (m.flags == Move.FLAG_PROMOTION)
            ? makePiece(m.promoted, color) : piece;
        squares[to] = placedPiece;
        zobristHash ^= ZOBRIST_PIECES[placedPiece][to];

        // castling rook move
        if (m.flags == Move.FLAG_CASTLE_K) {
            int rookFrom = to + 1, rookTo = to - 1;
            int rook = squares[rookFrom];
            zobristHash ^= ZOBRIST_PIECES[rook][rookFrom];
            squares[rookFrom] = EMPTY;
            squares[rookTo] = rook;
            zobristHash ^= ZOBRIST_PIECES[rook][rookTo];
        } else if (m.flags == Move.FLAG_CASTLE_Q) {
            int rookFrom = to - 2, rookTo = to + 1;
            int rook = squares[rookFrom];
            zobristHash ^= ZOBRIST_PIECES[rook][rookFrom];
            squares[rookFrom] = EMPTY;
            squares[rookTo] = rook;
            zobristHash ^= ZOBRIST_PIECES[rook][rookTo];
        }

        // update castling rights
        if (type == KING) {
            castling[color == WHITE ? 0 : 2] = false;
            castling[color == WHITE ? 1 : 3] = false;
        }
        if (from == 0 || to == 0)  castling[1] = false;
        if (from == 7 || to == 7)  castling[0] = false;
        if (from == 56 || to == 56) castling[3] = false;
        if (from == 63 || to == 63) castling[2] = false;

        // en passant square
        epSquare = NO_EP;
        if (type == PAWN && Math.abs(to - from) == 16) {
            epSquare = (from + to) / 2;
        }

        whiteToMove = !whiteToMove;
        if (!whiteToMove) fullMoveNumber++;

        // hash in new state
        if (!whiteToMove) zobristHash ^= ZOBRIST_SIDE;
        for (int i = 0; i < 4; i++) if (castling[i]) zobristHash ^= ZOBRIST_CASTLING[i];
        if (epSquare != NO_EP) zobristHash ^= ZOBRIST_EP[epSquare % 8];
    }

    public void unmakeMove(Move m) {
        UndoInfo u = history.pop();
        int from = m.from, to = m.to;
        int piece = m.piece;
        int color = pieceColor(piece);

        whiteToMove = !whiteToMove;
        if (whiteToMove) fullMoveNumber--;

        // restore piece to 'from'
        squares[from] = piece;

        // restore 'to'
        if (m.flags == Move.FLAG_EP) {
            squares[to] = EMPTY;
            int capSq = to + (color == WHITE ? -8 : 8);
            squares[capSq] = makePiece(PAWN, color ^ 1);
        } else {
            squares[to] = m.captured;
        }

        // undo castling rook
        if (m.flags == Move.FLAG_CASTLE_K) {
            int rookFrom = to + 1, rookTo = to - 1;
            squares[rookFrom] = squares[rookTo];
            squares[rookTo] = EMPTY;
        } else if (m.flags == Move.FLAG_CASTLE_Q) {
            int rookFrom = to - 2, rookTo = to + 1;
            squares[rookFrom] = squares[rookTo];
            squares[rookTo] = EMPTY;
        }

        castling = u.castling;
        epSquare = u.epSquare;
        halfMoveClock = u.halfMoveClock;
        zobristHash = u.zobristHash;
    }

    public int findKing(int color) {
        int king = makePiece(KING, color);
        for (int sq = 0; sq < 64; sq++)
            if (squares[sq] == king) return sq;
        return -1;
    }

    public boolean isInCheck(int color) {
        int kingSq = findKing(color);
        if (kingSq < 0) return false;
        return MoveGenerator.isSquareAttacked(this, kingSq, color ^ 1);
    }

    /** Make move from UCI string. Returns true if move was found and made. */
    public boolean makeMoveUCI(String uci) {
        var moves = MoveGenerator.generateLegalMoves(this);
        for (Move m : moves) {
            if (m.toUCI().equals(uci)) {
                makeMove(m);
                return true;
            }
        }
        return false;
    }

    public Board copy() {
        Board b = new Board();
        b.squares = squares.clone();
        b.whiteToMove = whiteToMove;
        b.castling = castling.clone();
        b.epSquare = epSquare;
        b.halfMoveClock = halfMoveClock;
        b.fullMoveNumber = fullMoveNumber;
        b.zobristHash = zobristHash;
        return b;
    }

    public static Board startPosition() {
        Board b = new Board();
        b.loadFEN("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        return b;
    }
}
