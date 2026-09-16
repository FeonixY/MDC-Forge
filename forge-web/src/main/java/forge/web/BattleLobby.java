package forge.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import forge.deck.Deck;

/**
 * Pairs two browser connections into one real human-vs-human {@link forge.gamemodes.match.HostedMatch},
 * keyed by a room token (the lobby room id). Each connection sends
 * {@code {"id":"joinbattle","room":"<id>","deck":"<Arena>","name":"..."}}; the first is parked
 * WAITING, and when the second arrives the match starts with both players' own guis + decks.
 *
 * <p>Only the pairing/lifecycle lives here; WebSocket frame sending stays in
 * {@link WebMatchServer}. Guarded by a single lock — joins are rare (match setup).
 */
public final class BattleLobby {
    private BattleLobby() {}

    /** One player's contribution to a pending match. */
    public static final class Seat {
        public final WebGuiGame gui;
        public final Deck deck;
        public final String name;
        Seat(WebGuiGame gui, Deck deck, String name) { this.gui = gui; this.deck = deck; this.name = name; }
    }

    private static final class Pending {
        final List<Seat> seats = new ArrayList<>(2);
        boolean started;
    }

    /** Result of a join attempt. */
    public enum Result { WAITING, STARTED, FULL }

    /** Max human seats per battle room (1v1 for now). */
    private static final int SEATS = 2;

    private static final Map<String, Pending> ROOMS = new ConcurrentHashMap<>();

    /**
     * A connection joins the battle room. Returns WAITING (parked for an opponent),
     * STARTED (this join completed the pairing and the match was launched), or FULL
     * (room already running / full — caller should reject).
     */
    public static synchronized Result join(String room, WebGuiGame gui, Deck deck, String name) {
        Pending p = ROOMS.computeIfAbsent(room, k -> new Pending());
        if (p.started || p.seats.size() >= SEATS) {
            return Result.FULL;
        }
        p.seats.add(new Seat(gui, deck, name));
        if (p.seats.size() >= SEATS) {
            p.started = true;
            ROOMS.remove(room);
            List<Seat> seats = new ArrayList<>(p.seats);
            MatchBootstrap.startHumanVsHuman(seats);
            return Result.STARTED;
        }
        return Result.WAITING;
    }

    /** Remove a still-waiting seat (its connection closed before the match started). No-op once started. */
    public static synchronized void leave(String room, WebGuiGame gui) {
        if (room == null) return;
        Pending p = ROOMS.get(room);
        if (p == null || p.started) return;
        p.seats.removeIf(s -> s.gui == gui);
        if (p.seats.isEmpty()) ROOMS.remove(room);
    }

    /** Names already seated in a room (for a "waiting for opponent" frame). */
    public static synchronized List<String> waitingNames(String room) {
        List<String> out = new ArrayList<>();
        Pending p = ROOMS.get(room);
        if (p != null) for (Seat s : p.seats) out.add(s.name);
        return out;
    }
}
