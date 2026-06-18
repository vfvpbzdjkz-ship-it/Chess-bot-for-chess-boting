package com.chessbot.engine;

import java.util.List;

public class Evaluator {

    // Material values in centipawns
    private static final int[] PIECE_VALUE = {0, 100, 320, 330, 500, 900, 20000};

    // Piece-square tables (from White's perspective, a1=index 0, h8=index 63)
    // These are standard tables from CPW, mirrored for use

    private static final int[] PST_PAWN = {
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
         5,  5, 10, 25, 25, 10,  5,  5,
         0,  0,  0, 20, 20,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0
    };

    private static final int[] PST_KNIGHT = {
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 10, 15, 15, 10,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 10, 15, 15, 10,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    };

    private static final int[] PST_BISHOP = {
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5, 10, 10,  5,  0,-10,
        -10,  5,  5, 10, 10,  5,  5,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10, 10, 10, 10, 10, 10, 10,-10,
        -10,  5,  0,  0,  0,  0,  5,-10,
        -20,-10,-10,-10,-10,-10,-10,-20
    };

    private static final int[] PST_ROOK = {
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
         0,  0,  0,  5,  5,  0,  0,  0
    };

    private static final int[] PST_QUEEN = {
        -20,-10,-10, -5, -5,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5,  5,  5,  5,  0,-10,
         -5,  0,  5,  5,  5,  5,  0, -5,
          0,  0,  5,  5,  5,  5,  0, -5,
        -10,  5,  5,  5,  5,  5,  0,-10,
        -10,  0,  5,  0,  0,  0,  0,-10,
        -20,-10,-10, -5, -5,-10,-10,-20
    };

    private static final int[] PST_KING_MID = {
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -20,-30,-30,-40,-40,-30,-30,-20,
        -10,-20,-20,-20,-20,-20,-20,-10,
         20, 20,  0,  0,  0,  0, 20, 20,
         20, 30, 10,  0,  0, 10, 30, 20
    };

    private static final int[] PST_KING_END = {
        -50,-40,-30,-20,-20,-30,-40,-50,
        -30,-20,-10,  0,  0,-10,-20,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-30,  0,  0,  0,  0,-30,-30,
        -50,-30,-30,-30,-30,-30,-30,-50
    };

    private static final int[][] PST = {
        null, PST_PAWN, PST_KNIGHT, PST_BISHOP, PST_ROOK, PST_QUEEN, null
    };

    /** Returns score relative to the side to move (positive = good). */
    public static int evaluate(Board b) {
        int score = 0;
        int phase = computePhase(b); // 0=endgame, 256=opening

        for (int sq = 0; sq < 64; sq++) {
            int piece = b.squares[sq];
            if (piece == Board.EMPTY) continue;
            int type  = Board.pieceType(piece);
            int color = Board.pieceColor(piece);
            int sign  = (color == Board.WHITE) ? 1 : -1;

            score += sign * PIECE_VALUE[type];
            score += sign * pst(type, sq, color, phase);
        }

        // Pawn structure bonuses/penalties
        score += pawnStructure(b);

        // Mobility bonus (rough approximation)
        score += mobilityBonus(b);

        // Return relative to side to move
        return b.whiteToMove ? score : -score;
    }

    private static int computePhase(Board b) {
        int material = 0;
        for (int sq = 0; sq < 64; sq++) {
            int p = b.squares[sq];
            if (p == Board.EMPTY) continue;
            int t = Board.pieceType(p);
            if (t == Board.KNIGHT || t == Board.BISHOP) material += 1;
            else if (t == Board.ROOK) material += 2;
            else if (t == Board.QUEEN) material += 4;
        }
        return Math.min(256, material * 256 / 24);
    }

    private static int pst(int type, int sq, int color, int phase) {
        // Flip square for black (so PST is from each side's perspective)
        int idx = (color == Board.WHITE) ? (7 - sq / 8) * 8 + sq % 8 : sq;
        if (type == Board.KING) {
            int midVal = PST_KING_MID[idx];
            int endVal = PST_KING_END[idx];
            return (midVal * phase + endVal * (256 - phase)) / 256;
        }
        if (PST[type] == null) return 0;
        return PST[type][idx];
    }

    private static int pawnStructure(Board b) {
        int score = 0;
        // Count pawns per file for each color
        int[] wPawns = new int[8], bPawns = new int[8];
        int[] wPawnRank = new int[8], bPawnRank = new int[8]; // most advanced
        for (int f = 0; f < 8; f++) { wPawnRank[f] = 0; bPawnRank[f] = 7; }

        for (int sq = 0; sq < 64; sq++) {
            int p = b.squares[sq];
            if (Board.pieceType(p) != Board.PAWN) continue;
            int f = sq % 8, r = sq / 8;
            if (Board.pieceColor(p) == Board.WHITE) {
                wPawns[f]++;
                wPawnRank[f] = Math.max(wPawnRank[f], r);
            } else {
                bPawns[f]++;
                bPawnRank[f] = Math.min(bPawnRank[f], r);
            }
        }

        for (int f = 0; f < 8; f++) {
            // Doubled pawns penalty
            if (wPawns[f] > 1) score -= 20 * (wPawns[f] - 1);
            if (bPawns[f] > 1) score += 20 * (bPawns[f] - 1);
            // Isolated pawns penalty
            boolean wIsolated = (f == 0 || wPawns[f-1] == 0) && (f == 7 || wPawns[f+1] == 0);
            boolean bIsolated = (f == 0 || bPawns[f-1] == 0) && (f == 7 || bPawns[f+1] == 0);
            if (wPawns[f] > 0 && wIsolated) score -= 15;
            if (bPawns[f] > 0 && bIsolated) score += 15;
            // Passed pawns bonus
            if (wPawns[f] > 0) {
                boolean passed = true;
                for (int ff = Math.max(0, f-1); ff <= Math.min(7, f+1); ff++)
                    if (bPawns[ff] > 0 && bPawnRank[ff] > wPawnRank[f]) { passed = false; break; }
                if (passed) score += 20 + wPawnRank[f] * 10;
            }
            if (bPawns[f] > 0) {
                boolean passed = true;
                for (int ff = Math.max(0, f-1); ff <= Math.min(7, f+1); ff++)
                    if (wPawns[ff] > 0 && wPawnRank[ff] < bPawnRank[f]) { passed = false; break; }
                if (passed) score -= 20 + (7 - bPawnRank[f]) * 10;
            }
        }
        return score;
    }

    private static int mobilityBonus(Board b) {
        // Count legal moves for each side as a simple mobility metric
        List<Move> moves = MoveGenerator.generateLegalMoves(b);
        int myMobility = moves.size();
        // Toggle side and count opponent moves
        b.whiteToMove = !b.whiteToMove;
        List<Move> oppMoves = MoveGenerator.generateLegalMoves(b);
        int oppMobility = oppMoves.size();
        b.whiteToMove = !b.whiteToMove;
        int diff = myMobility - oppMobility;
        return b.whiteToMove ? diff * 2 : -diff * 2;
    }

    public static int pieceValue(int pieceType) {
        return PIECE_VALUE[pieceType];
    }

    // MVV-LVA table [victim][attacker]
    private static final int[][] MVV_LVA = new int[7][7];
    static {
        for (int v = 1; v <= 6; v++)
            for (int a = 1; a <= 6; a++)
                MVV_LVA[v][a] = PIECE_VALUE[v] * 10 - PIECE_VALUE[a];
    }

    public static int mvvLva(Move m) {
        int vic = Board.pieceType(m.captured);
        int att = Board.pieceType(m.piece);
        if (vic == 0) return 0;
        return MVV_LVA[vic][att];
    }
}
