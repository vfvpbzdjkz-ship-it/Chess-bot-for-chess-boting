package com.chessbot.lichess;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public class LichessClient {
    private static final String BASE = "https://lichess.org";

    private final HttpClient http;
    private String token;

    public LichessClient() {
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public void setToken(String token) {
        this.token = token.trim();
    }

    public String getToken() { return token; }

    // ---- account ----

    public String getAccountUsername() throws Exception {
        String body = get("/api/account");
        return extractString(body, "name");
    }

    public boolean upgradeToBot() throws Exception {
        HttpResponse<String> resp = post("/api/bot/account/upgrade", "");
        return resp.statusCode() == 200;
    }

    // ---- bot list ----

    /** Returns list of "username" strings of online bots (up to 50). */
    public List<String> getOnlineBots() throws Exception {
        List<String> bots = new ArrayList<>();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/bot/online?nb=50"))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() != 200) return bots;
        // NDJSON - each line is a JSON object
        for (String line : resp.body().split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String name = extractString(line, "name");
            if (name != null && !name.isEmpty()) bots.add(name);
        }
        return bots;
    }

    // ---- challenge ----

    /**
     * Challenge a bot.
     * @param username target bot username
     * @param rated    true for rated game
     * @param clockSeconds base time in seconds (e.g. 600 for 10 min)
     * @param clockIncrement increment in seconds
     * @return challengeId or null on failure
     */
    public String challengeBot(String username, boolean rated, int clockSeconds, int clockIncrement) throws Exception {
        String body = "rated=" + rated
            + "&clock.limit=" + clockSeconds
            + "&clock.increment=" + clockIncrement
            + "&color=random"
            + "&variant=standard";
        HttpResponse<String> resp = post("/api/challenge/" + username, body);
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new IOException("Challenge failed: " + resp.statusCode() + " " + resp.body());
        }
        return extractString(resp.body(), "id");
    }

    // ---- event stream ----

    /**
     * Stream incoming events (game starts, challenges, etc.)
     * Calls lineConsumer for each NDJSON line received.
     * Blocks until cancelled; run in a thread.
     */
    public void streamEvents(Consumer<String> lineConsumer, BooleanSupplier cancelled) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/stream/event"))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofMinutes(60))
            .GET()
            .build();
        HttpResponse<InputStream> resp = http.send(req, BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lineConsumer.accept(line);
            }
        }
    }

    // ---- game stream ----

    /**
     * Stream game state for a given game ID.
     * Calls lineConsumer for each NDJSON line.
     */
    public void streamGame(String gameId, Consumer<String> lineConsumer, BooleanSupplier cancelled) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/bot/game/stream/" + gameId))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofMinutes(90))
            .GET()
            .build();
        HttpResponse<InputStream> resp = http.send(req, BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lineConsumer.accept(line);
            }
        }
    }

    // ---- make move ----

    public boolean makeMove(String gameId, String uciMove) throws Exception {
        HttpResponse<String> resp = post("/api/bot/game/" + gameId + "/move/" + uciMove, "");
        return resp.statusCode() == 200;
    }

    // ---- resign / abort ----

    public boolean resign(String gameId) throws Exception {
        HttpResponse<String> resp = post("/api/bot/game/" + gameId + "/resign", "");
        return resp.statusCode() == 200;
    }

    public boolean abort(String gameId) throws Exception {
        HttpResponse<String> resp = post("/api/bot/game/" + gameId + "/abort", "");
        return resp.statusCode() == 200;
    }

    // ---- accept / decline challenge ----

    public boolean acceptChallenge(String challengeId) throws Exception {
        HttpResponse<String> resp = post("/api/challenge/" + challengeId + "/accept", "");
        return resp.statusCode() == 200;
    }

    public boolean declineChallenge(String challengeId) throws Exception {
        HttpResponse<String> resp = post("/api/challenge/" + challengeId + "/decline", "");
        return resp.statusCode() == 200;
    }

    // ---- HTTP helpers ----

    private String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        return http.send(req, BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> post(String path, String formBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();
        return http.send(req, BodyHandlers.ofString());
    }

    // ---- minimal JSON helpers ----

    /** Extract a string value for a key like "name" from a flat JSON object. */
    public static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    public static String extractStringNested(String json, String key) {
        return extractString(json, key);
    }

    public static boolean extractBoolean(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return false;
        String rest = json.substring(colon + 1).stripLeading();
        return rest.startsWith("true");
    }

    public static String getEventType(String json) {
        return extractString(json, "type");
    }

    /** Extract a sub-object as a string (everything between matching braces after key). */
    public static String extractObject(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int brace = json.indexOf('{', colon);
        if (brace < 0) return null;
        int depth = 0, i = brace;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(brace, i + 1); }
            i++;
        }
        return null;
    }

    @FunctionalInterface
    public interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
