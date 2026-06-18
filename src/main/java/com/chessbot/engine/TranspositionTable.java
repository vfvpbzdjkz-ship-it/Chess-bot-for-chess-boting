package com.chessbot.engine;

public class TranspositionTable {
    public static final int EXACT = 0;
    public static final int LOWER = 1; // alpha (lower bound)
    public static final int UPPER = 2; // beta  (upper bound)

    private static final int SIZE = 1 << 22; // ~4M entries
    private static final int MASK = SIZE - 1;

    private final long[]  keys   = new long[SIZE];
    private final int[]   scores = new int[SIZE];
    private final int[]   depths = new int[SIZE];
    private final byte[]  flags  = new byte[SIZE];
    private final int[]   bestFrom = new int[SIZE];
    private final int[]   bestTo   = new int[SIZE];

    public static final int NO_ENTRY = Integer.MIN_VALUE;

    public void store(long key, int depth, int score, int flag, Move best) {
        int idx = (int)(key & MASK);
        // Always-replace strategy
        keys[idx]   = key;
        scores[idx] = score;
        depths[idx] = depth;
        flags[idx]  = (byte) flag;
        bestFrom[idx] = (best != null) ? best.from : -1;
        bestTo[idx]   = (best != null) ? best.to   : -1;
    }

    public int probe(long key, int depth, int alpha, int beta) {
        int idx = (int)(key & MASK);
        if (keys[idx] != key) return NO_ENTRY;
        if (depths[idx] < depth) return NO_ENTRY;
        int score = scores[idx];
        int flag  = flags[idx];
        if (flag == EXACT) return score;
        if (flag == LOWER && score >= beta)  return score;
        if (flag == UPPER && score <= alpha) return score;
        return NO_ENTRY;
    }

    /** Returns best move from TT, or null if none / key mismatch */
    public Move getBestMove(long key, Board b) {
        int idx = (int)(key & MASK);
        if (keys[idx] != key) return null;
        int from = bestFrom[idx], to = bestTo[idx];
        if (from < 0) return null;
        // Find matching legal move
        for (Move m : MoveGenerator.generateLegalMoves(b)) {
            if (m.from == from && m.to == to) return m;
        }
        return null;
    }

    public void clear() {
        java.util.Arrays.fill(keys, 0L);
    }
}
