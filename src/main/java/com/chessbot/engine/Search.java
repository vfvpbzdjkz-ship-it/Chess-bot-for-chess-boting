package com.chessbot.engine;

import java.util.List;

public class Search {
    private static final int INF       = 1_000_000;
    private static final int MATE_SCORE = 900_000;
    private static final int DRAW_SCORE = 0;

    private final TranspositionTable tt = new TranspositionTable();
    private final int[][] killers = new int[128][2]; // killers[ply][0/1] = move hash
    private final int[][] history  = new int[15][64];

    private long startTime;
    private long timeLimitMs;
    private boolean stopped;
    private Move bestMoveRoot;
    private int  nodesSearched;

    public Search() {}

    /** Find the best move with iterative deepening within the time limit. */
    public Move findBestMove(Board board, long timeLimitMs) {
        this.startTime   = System.currentTimeMillis();
        this.timeLimitMs = timeLimitMs;
        this.stopped     = false;
        this.nodesSearched = 0;
        this.bestMoveRoot  = null;
        clearKillersHistory();

        List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
        if (legalMoves.isEmpty()) return null;
        if (legalMoves.size() == 1) return legalMoves.get(0);

        bestMoveRoot = legalMoves.get(0);

        for (int depth = 1; depth <= 64; depth++) {
            Move candidate = searchRoot(board, depth);
            if (!stopped && candidate != null) {
                bestMoveRoot = candidate;
            }
            if (stopped) break;
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= timeLimitMs * 2 / 3) break;
        }
        return bestMoveRoot;
    }

    private Move searchRoot(Board board, int depth) {
        int alpha = -INF, beta = INF;
        Move best = null;
        List<Move> moves = MoveGenerator.generateLegalMoves(board);
        orderMoves(moves, board, 0);

        for (Move m : moves) {
            if (timeUp()) { stopped = true; break; }
            board.makeMove(m);
            int score = -negamax(board, depth - 1, -beta, -alpha, 1);
            board.unmakeMove(m);
            if (stopped) break;
            if (score > alpha) {
                alpha = score;
                best = m;
            }
        }
        return best;
    }

    private int negamax(Board board, int depth, int alpha, int beta, int ply) {
        nodesSearched++;
        if (timeUp()) { stopped = true; return 0; }

        // Draw detection
        if (board.halfMoveClock >= 100) return DRAW_SCORE;

        // TT lookup
        int ttScore = tt.probe(board.zobristHash, depth, alpha, beta);
        if (ttScore != TranspositionTable.NO_ENTRY) return ttScore;

        if (depth <= 0) return quiescence(board, alpha, beta, ply);

        List<Move> moves = MoveGenerator.generateLegalMoves(board);
        if (moves.isEmpty()) {
            return board.isInCheck(board.whiteToMove ? Board.WHITE : Board.BLACK)
                ? -(MATE_SCORE - ply) : DRAW_SCORE;
        }

        // Null move pruning (skip in check, and only if not pawn endgame)
        int color = board.whiteToMove ? Board.WHITE : Board.BLACK;
        boolean inCheck = board.isInCheck(color);
        if (!inCheck && depth >= 3 && hasMajorPieces(board, color)) {
            board.whiteToMove = !board.whiteToMove;
            int nullScore = -negamax(board, depth - 3, -beta, -beta + 1, ply + 1);
            board.whiteToMove = !board.whiteToMove;
            if (nullScore >= beta) return beta;
        }

        orderMoves(moves, board, ply);

        int flag = TranspositionTable.UPPER;
        Move bestMove = null;
        int moveCount = 0;

        for (Move m : moves) {
            if (timeUp()) { stopped = true; break; }
            board.makeMove(m);
            int score;
            // Late move reduction
            if (moveCount >= 4 && depth >= 3 && !m.isCapture() && !m.isPromotion() && !inCheck) {
                int reduction = 1 + (moveCount >= 8 ? 1 : 0);
                score = -negamax(board, depth - 1 - reduction, -alpha - 1, -alpha, ply + 1);
                if (score > alpha)
                    score = -negamax(board, depth - 1, -beta, -alpha, ply + 1);
            } else {
                score = -negamax(board, depth - 1, -beta, -alpha, ply + 1);
            }
            board.unmakeMove(m);
            if (stopped) break;

            moveCount++;
            if (score >= beta) {
                // Killer move update
                if (!m.isCapture()) {
                    killers[ply][1] = killers[ply][0];
                    killers[ply][0] = moveHash(m);
                    history[m.piece][m.to] += depth * depth;
                }
                tt.store(board.zobristHash, depth, beta, TranspositionTable.LOWER, m);
                return beta;
            }
            if (score > alpha) {
                alpha = score;
                flag = TranspositionTable.EXACT;
                bestMove = m;
            }
        }

        if (!stopped)
            tt.store(board.zobristHash, depth, alpha, flag, bestMove);
        return alpha;
    }

    private int quiescence(Board board, int alpha, int beta, int ply) {
        nodesSearched++;
        if (timeUp()) { stopped = true; return 0; }

        int standPat = Evaluator.evaluate(board);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        List<Move> captures = MoveGenerator.generateLegalCaptures(board);
        orderMoves(captures, board, ply);

        for (Move m : captures) {
            // Delta pruning
            int gain = Evaluator.pieceValue(Board.pieceType(m.captured)) + 200;
            if (standPat + gain < alpha && !m.isPromotion()) continue;

            board.makeMove(m);
            int score = -quiescence(board, -beta, -alpha, ply + 1);
            board.unmakeMove(m);
            if (stopped) break;

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private void orderMoves(List<Move> moves, Board board, int ply) {
        // Score each move
        Move ttBest = tt.getBestMove(board.zobristHash, board);
        for (Move m : moves) {
            if (ttBest != null && m.from == ttBest.from && m.to == ttBest.to) {
                m.score = 2_000_000;
            } else if (m.isCapture()) {
                m.score = 1_000_000 + Evaluator.mvvLva(m);
            } else if (m.isPromotion()) {
                m.score = 900_000;
            } else if (ply < 128 && moveHash(m) == killers[ply][0]) {
                m.score = 800_000;
            } else if (ply < 128 && moveHash(m) == killers[ply][1]) {
                m.score = 700_000;
            } else {
                m.score = history[m.piece][m.to];
            }
        }
        moves.sort((a, b2) -> Integer.compare(b2.score, a.score));
    }

    private boolean hasMajorPieces(Board board, int color) {
        for (int sq = 0; sq < 64; sq++) {
            int p = board.squares[sq];
            if (p == Board.EMPTY || Board.pieceColor(p) != color) continue;
            int t = Board.pieceType(p);
            if (t == Board.ROOK || t == Board.QUEEN || t == Board.BISHOP || t == Board.KNIGHT)
                return true;
        }
        return false;
    }

    private boolean timeUp() {
        return (nodesSearched & 2047) == 0
            && (System.currentTimeMillis() - startTime) >= timeLimitMs;
    }

    private static int moveHash(Move m) {
        return m.from * 64 + m.to;
    }

    private void clearKillersHistory() {
        for (int[] k : killers) { k[0] = 0; k[1] = 0; }
        for (int[] h : history) java.util.Arrays.fill(h, 0);
    }

    public int getNodesSearched() { return nodesSearched; }
}
