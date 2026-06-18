package com.chessbot.engine;

import java.util.ArrayList;
import java.util.List;

public class MoveGenerator {

    private static final int[] KNIGHT_OFFSETS = {-17,-15,-10,-6,6,10,15,17};
    private static final int[] KING_OFFSETS   = {-9,-8,-7,-1,1,7,8,9};
    private static final int[] BISHOP_DIRS    = {-9,-7,7,9};
    private static final int[] ROOK_DIRS      = {-8,-1,1,8};

    public static List<Move> generatePseudoLegalMoves(Board b) {
        List<Move> moves = new ArrayList<>(64);
        int color = b.whiteToMove ? Board.WHITE : Board.BLACK;
        for (int sq = 0; sq < 64; sq++) {
            int piece = b.squares[sq];
            if (piece == Board.EMPTY || Board.pieceColor(piece) != color) continue;
            int type = Board.pieceType(piece);
            switch (type) {
                case Board.PAWN   -> generatePawnMoves(b, sq, piece, color, moves);
                case Board.KNIGHT -> generateLeaperMoves(b, sq, piece, KNIGHT_OFFSETS, moves);
                case Board.BISHOP -> generateSliderMoves(b, sq, piece, BISHOP_DIRS, moves);
                case Board.ROOK   -> generateSliderMoves(b, sq, piece, ROOK_DIRS, moves);
                case Board.QUEEN  -> {
                    generateSliderMoves(b, sq, piece, BISHOP_DIRS, moves);
                    generateSliderMoves(b, sq, piece, ROOK_DIRS, moves);
                }
                case Board.KING   -> generateKingMoves(b, sq, piece, color, moves);
            }
        }
        return moves;
    }

    public static List<Move> generateLegalMoves(Board b) {
        List<Move> pseudo = generatePseudoLegalMoves(b);
        List<Move> legal = new ArrayList<>(pseudo.size());
        int color = b.whiteToMove ? Board.WHITE : Board.BLACK;
        for (Move m : pseudo) {
            b.makeMove(m);
            if (!b.isInCheck(color)) legal.add(m);
            b.unmakeMove(m);
        }
        return legal;
    }

    public static List<Move> generateLegalCaptures(Board b) {
        List<Move> all = generateLegalMoves(b);
        List<Move> caps = new ArrayList<>();
        for (Move m : all) if (m.isCapture()) caps.add(m);
        return caps;
    }

    private static void generatePawnMoves(Board b, int sq, int piece, int color, List<Move> moves) {
        int rank = sq / 8, file = sq % 8;
        int dir = color == Board.WHITE ? 1 : -1;
        int startRank = color == Board.WHITE ? 1 : 6;
        int promoteRank = color == Board.WHITE ? 6 : 1;

        // single push
        int to = sq + dir * 8;
        if (to >= 0 && to < 64 && b.squares[to] == Board.EMPTY) {
            if (rank == promoteRank) addPromotions(sq, to, piece, Board.EMPTY, moves);
            else moves.add(new Move(sq, to, piece, Board.EMPTY, 0, Move.FLAG_NONE));
            // double push
            if (rank == startRank) {
                int to2 = sq + dir * 16;
                if (b.squares[to2] == Board.EMPTY)
                    moves.add(new Move(sq, to2, piece, Board.EMPTY, 0, Move.FLAG_NONE));
            }
        }

        // captures
        int[] captureFiles = {file - 1, file + 1};
        for (int cf : captureFiles) {
            if (cf < 0 || cf > 7) continue;
            int capSq = (rank + dir) * 8 + cf;
            if (capSq < 0 || capSq >= 64) continue;
            int captured = b.squares[capSq];
            if (captured != Board.EMPTY && Board.pieceColor(captured) != color) {
                if (rank == promoteRank) addPromotions(sq, capSq, piece, captured, moves);
                else moves.add(new Move(sq, capSq, piece, captured, 0, Move.FLAG_NONE));
            }
            // en passant
            if (capSq == b.epSquare) {
                moves.add(new Move(sq, capSq, piece, Board.makePiece(Board.PAWN, color ^ 1), 0, Move.FLAG_EP));
            }
        }
    }

    private static void addPromotions(int from, int to, int piece, int captured, List<Move> moves) {
        for (int pt : new int[]{Board.QUEEN, Board.ROOK, Board.BISHOP, Board.KNIGHT})
            moves.add(new Move(from, to, piece, captured, pt, Move.FLAG_PROMOTION));
    }

    private static void generateLeaperMoves(Board b, int sq, int piece, int[] offsets, List<Move> moves) {
        int color = Board.pieceColor(piece);
        int rank = sq / 8, file = sq % 8;
        for (int off : offsets) {
            int to = sq + off;
            if (to < 0 || to >= 64) continue;
            // bounds check for wrapping
            int toFile = to % 8;
            if (Math.abs(toFile - file) > 2) continue;
            int captured = b.squares[to];
            if (captured != Board.EMPTY && Board.pieceColor(captured) == color) continue;
            moves.add(new Move(sq, to, piece, captured == Board.EMPTY ? 0 : captured, 0, Move.FLAG_NONE));
        }
    }

    private static void generateSliderMoves(Board b, int sq, int piece, int[] dirs, List<Move> moves) {
        int color = Board.pieceColor(piece);
        for (int dir : dirs) {
            int cur = sq;
            while (true) {
                int prevFile = cur % 8;
                int to = cur + dir;
                if (to < 0 || to >= 64) break;
                int toFile = to % 8;
                // prevent wrap-around for horizontal moves
                if (Math.abs(dir) == 1 && Math.abs(toFile - prevFile) != 1) break;
                if ((dir == 7 || dir == -9) && toFile != prevFile - 1) break;
                if ((dir == 9 || dir == -7) && toFile != prevFile + 1) break;
                int captured = b.squares[to];
                if (captured != Board.EMPTY) {
                    if (Board.pieceColor(captured) != color)
                        moves.add(new Move(sq, to, piece, captured, 0, Move.FLAG_NONE));
                    break;
                }
                moves.add(new Move(sq, to, piece, 0, 0, Move.FLAG_NONE));
                cur = to;
            }
        }
    }

    private static void generateKingMoves(Board b, int sq, int piece, int color, List<Move> moves) {
        generateLeaperMoves(b, sq, piece, KING_OFFSETS, moves);
        // castling
        if (color == Board.WHITE) {
            if (b.castling[0] && b.squares[5] == Board.EMPTY && b.squares[6] == Board.EMPTY
                    && !isSquareAttacked(b, 4, Board.BLACK)
                    && !isSquareAttacked(b, 5, Board.BLACK)
                    && !isSquareAttacked(b, 6, Board.BLACK))
                moves.add(new Move(4, 6, piece, 0, 0, Move.FLAG_CASTLE_K));
            if (b.castling[1] && b.squares[3] == Board.EMPTY && b.squares[2] == Board.EMPTY && b.squares[1] == Board.EMPTY
                    && !isSquareAttacked(b, 4, Board.BLACK)
                    && !isSquareAttacked(b, 3, Board.BLACK)
                    && !isSquareAttacked(b, 2, Board.BLACK))
                moves.add(new Move(4, 2, piece, 0, 0, Move.FLAG_CASTLE_Q));
        } else {
            if (b.castling[2] && b.squares[61] == Board.EMPTY && b.squares[62] == Board.EMPTY
                    && !isSquareAttacked(b, 60, Board.WHITE)
                    && !isSquareAttacked(b, 61, Board.WHITE)
                    && !isSquareAttacked(b, 62, Board.WHITE))
                moves.add(new Move(60, 62, piece, 0, 0, Move.FLAG_CASTLE_K));
            if (b.castling[3] && b.squares[59] == Board.EMPTY && b.squares[58] == Board.EMPTY && b.squares[57] == Board.EMPTY
                    && !isSquareAttacked(b, 60, Board.WHITE)
                    && !isSquareAttacked(b, 59, Board.WHITE)
                    && !isSquareAttacked(b, 58, Board.WHITE))
                moves.add(new Move(60, 58, piece, 0, 0, Move.FLAG_CASTLE_Q));
        }
    }

    public static boolean isSquareAttacked(Board b, int sq, int byColor) {
        // pawns
        int pawnDir = byColor == Board.WHITE ? -1 : 1;
        int[] pawnFiles = {sq % 8 - 1, sq % 8 + 1};
        for (int pf : pawnFiles) {
            if (pf < 0 || pf > 7) continue;
            int pawnSq = sq + pawnDir * 8;
            if (pawnSq < 0 || pawnSq >= 64) continue;
            int p = b.squares[pawnSq];
            if (p != Board.EMPTY && Board.pieceType(p) == Board.PAWN && Board.pieceColor(p) == byColor
                    && pawnSq % 8 == pf)
                return true;
        }
        // knights
        int file = sq % 8;
        for (int off : KNIGHT_OFFSETS) {
            int t = sq + off;
            if (t < 0 || t >= 64) continue;
            if (Math.abs(t % 8 - file) > 2) continue;
            int p = b.squares[t];
            if (p != Board.EMPTY && Board.pieceType(p) == Board.KNIGHT && Board.pieceColor(p) == byColor)
                return true;
        }
        // king
        for (int off : KING_OFFSETS) {
            int t = sq + off;
            if (t < 0 || t >= 64) continue;
            if (Math.abs(t % 8 - sq % 8) > 1) continue;
            int p = b.squares[t];
            if (p != Board.EMPTY && Board.pieceType(p) == Board.KING && Board.pieceColor(p) == byColor)
                return true;
        }
        // bishops/queens (diagonals)
        for (int dir : BISHOP_DIRS) {
            int cur = sq;
            while (true) {
                int pf2 = cur % 8;
                int t = cur + dir;
                if (t < 0 || t >= 64) break;
                int tf = t % 8;
                if (Math.abs(tf - pf2) != 1) break;
                int p = b.squares[t];
                if (p != Board.EMPTY) {
                    if (Board.pieceColor(p) == byColor &&
                        (Board.pieceType(p) == Board.BISHOP || Board.pieceType(p) == Board.QUEEN))
                        return true;
                    break;
                }
                cur = t;
            }
        }
        // rooks/queens (orthogonals)
        for (int dir : ROOK_DIRS) {
            int cur = sq;
            while (true) {
                int pf2 = cur % 8;
                int t = cur + dir;
                if (t < 0 || t >= 64) break;
                int tf = t % 8;
                if (Math.abs(dir) == 1 && Math.abs(tf - pf2) != 1) break;
                int p = b.squares[t];
                if (p != Board.EMPTY) {
                    if (Board.pieceColor(p) == byColor &&
                        (Board.pieceType(p) == Board.ROOK || Board.pieceType(p) == Board.QUEEN))
                        return true;
                    break;
                }
                cur = t;
            }
        }
        return false;
    }
}
