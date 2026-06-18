package com.chessbot.engine;

public class Move {
    public static final int FLAG_NONE       = 0;
    public static final int FLAG_EP         = 1;
    public static final int FLAG_CASTLE_K   = 2;
    public static final int FLAG_CASTLE_Q   = 3;
    public static final int FLAG_PROMOTION  = 4;

    public int from;
    public int to;
    public int piece;       // moving piece
    public int captured;    // captured piece (0 = none)
    public int promoted;    // promoted piece type (0 = none)
    public int flags;
    public int score;       // for move ordering

    public Move(int from, int to, int piece, int captured, int promoted, int flags) {
        this.from = from;
        this.to = to;
        this.piece = piece;
        this.captured = captured;
        this.promoted = promoted;
        this.flags = flags;
    }

    public boolean isCapture() {
        return captured != Board.EMPTY || flags == FLAG_EP;
    }

    public boolean isPromotion() {
        return flags == FLAG_PROMOTION;
    }

    public boolean isCastle() {
        return flags == FLAG_CASTLE_K || flags == FLAG_CASTLE_Q;
    }

    /** Convert to UCI string like "e2e4" or "e7e8q" */
    public String toUCI() {
        String s = squareName(from) + squareName(to);
        if (flags == FLAG_PROMOTION) {
            char[] names = {'?','p','n','b','r','q','k'};
            s += names[promoted];
        }
        return s;
    }

    public static String squareName(int sq) {
        char file = (char) ('a' + (sq % 8));
        char rank = (char) ('1' + (sq / 8));
        return "" + file + rank;
    }

    public static int squareIndex(String name) {
        int file = name.charAt(0) - 'a';
        int rank = name.charAt(1) - '1';
        return rank * 8 + file;
    }

    @Override
    public String toString() {
        return toUCI();
    }
}
