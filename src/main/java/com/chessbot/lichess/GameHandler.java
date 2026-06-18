package com.chessbot.lichess;

import com.chessbot.engine.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages a single Lichess bot game: streams the game, plays moves using the engine.
 */
public class GameHandler {
    private final LichessClient client;
    private final String gameId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Search search = new Search();

    // Callbacks for the GUI
    private Consumer<String[][]> onBoardUpdate;  // fenParts or movelist
    private Consumer<String>     onStatusUpdate;
    private BiConsumer<String, String> onTimeUpdate; // white/black time

    private String ourColor; // "white" or "black"
    private Board board;

    public GameHandler(LichessClient client, String gameId) {
        this.client = client;
        this.gameId = gameId;
    }

    public void setOnBoardUpdate(Consumer<String[][]> cb)  { this.onBoardUpdate = cb; }
    public void setOnStatusUpdate(Consumer<String> cb)      { this.onStatusUpdate = cb; }
    public void setOnTimeUpdate(BiConsumer<String,String> cb) { this.onTimeUpdate = cb; }

    public void start() {
        new Thread(this::run, "game-" + gameId).start();
    }

    public void cancel() {
        cancelled.set(true);
    }

    private void run() {
        try {
            status("Connecting to game " + gameId + "...");
            client.streamGame(gameId, this::handleLine, cancelled::get);
        } catch (Exception e) {
            if (!cancelled.get()) status("Game stream error: " + e.getMessage());
        }
        status("Game ended.");
    }

    private void handleLine(String line) {
        try {
            String type = LichessClient.getEventType(line);
            if (type == null) return;

            switch (type) {
                case "gameFull" -> handleGameFull(line);
                case "gameState" -> handleGameState(line);
                case "chatLine" -> {} // ignore chat
            }
        } catch (Exception e) {
            status("Error handling game event: " + e.getMessage());
        }
    }

    private void handleGameFull(String json) throws Exception {
        // Determine our color from the player objects
        String white = LichessClient.extractString(json, "id");
        // Extract white and black player ids
        String whiteObj = LichessClient.extractObject(json, "white");
        String blackObj = LichessClient.extractObject(json, "black");
        String myName = client.getAccountUsername();

        String whiteId = whiteObj != null ? LichessClient.extractString(whiteObj, "id") : null;
        String blackId = blackObj != null ? LichessClient.extractString(blackObj, "id") : null;

        if (myName != null && myName.equalsIgnoreCase(blackId)) {
            ourColor = "black";
        } else {
            ourColor = "white";
        }
        status("Playing as " + ourColor);

        // Get initial position
        String initialFen = LichessClient.extractString(json, "fen");
        board = new Board();
        if (initialFen != null && !initialFen.isEmpty() && !initialFen.equals("startpos")) {
            board.loadFEN(initialFen);
        } else {
            board.loadFEN("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        }

        // Process the embedded state
        String stateObj = LichessClient.extractObject(json, "state");
        if (stateObj != null) processState(stateObj);
    }

    private void handleGameState(String json) throws Exception {
        if (board == null) {
            board = Board.startPosition();
        }
        processState(json);
    }

    private void processState(String stateJson) throws Exception {
        String status = LichessClient.extractString(stateJson, "status");
        if (status != null && !status.equals("started") && !status.equals("created")) {
            status("Game over: " + status);
            cancelled.set(true);
            return;
        }

        // Reconstruct board from moves list
        String moves = LichessClient.extractString(stateJson, "moves");
        board = Board.startPosition();
        if (moves != null && !moves.isEmpty()) {
            for (String mv : moves.trim().split("\\s+")) {
                if (!mv.isEmpty()) board.makeMoveUCI(mv);
            }
        }

        updateBoardDisplay();

        // Time
        String wtime = LichessClient.extractString(stateJson, "wtime");
        String btime = LichessClient.extractString(stateJson, "btime");
        if (onTimeUpdate != null && wtime != null && btime != null) {
            onTimeUpdate.accept(formatMs(Long.parseLong(wtime)), formatMs(Long.parseLong(btime)));
        }

        // Is it our turn?
        boolean whiteTurn = board.whiteToMove;
        boolean ourTurn = (whiteTurn && "white".equals(ourColor))
                       || (!whiteTurn && "black".equals(ourColor));

        if (ourTurn && !cancelled.get()) {
            status("Thinking...");
            long timeMs = ourColor.equals("white") ? parseLong(wtime) : parseLong(btime);
            long thinkTime = computeThinkTime(timeMs, board.fullMoveNumber);
            Move bestMove = search.findBestMove(board, thinkTime);
            if (bestMove != null) {
                String uci = bestMove.toUCI();
                status("Playing " + uci);
                client.makeMove(gameId, uci);
            } else {
                status("No legal moves — game over.");
            }
        } else {
            status("Waiting for opponent...");
        }
    }

    private long computeThinkTime(long remainingMs, int moveNumber) {
        // Allocate ~1/20 of remaining time per move, with a min/max
        if (remainingMs <= 0) return 1000;
        long base = remainingMs / 20;
        // In the opening be quicker, midgame use full allocation
        if (moveNumber < 10) base = Math.min(base, 3000);
        return Math.max(500, Math.min(base, 15000));
    }

    private void updateBoardDisplay() {
        if (onBoardUpdate == null) return;
        String[][] grid = new String[8][8];
        for (int sq = 0; sq < 64; sq++) {
            int p = board.squares[sq];
            int rank = sq / 8, file = sq % 8;
            grid[rank][file] = pieceSymbol(p);
        }
        onBoardUpdate.accept(grid);
    }

    private static String pieceSymbol(int piece) {
        if (piece == Board.EMPTY) return ".";
        int type  = Board.pieceType(piece);
        int color = Board.pieceColor(piece);
        String[] symbols = {".", "P","N","B","R","Q","K"};
        String s = symbols[type];
        return color == Board.WHITE ? s : s.toLowerCase();
    }

    private static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long secs  = ms / 1000;
        long mins  = secs / 60;
        long rem   = secs % 60;
        return String.format("%d:%02d", mins, rem);
    }

    private static long parseLong(String s) {
        if (s == null) return 60000;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 60000; }
    }

    private void status(String msg) {
        if (onStatusUpdate != null) onStatusUpdate.accept(msg);
    }

    public String getOurColor() { return ourColor; }
    public Board getBoard() { return board; }
}
