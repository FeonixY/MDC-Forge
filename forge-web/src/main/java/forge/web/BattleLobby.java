package forge.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import forge.deck.Deck;

/**
 * Pairs two browser connections into one real human-vs-human {@link forge.gamemodes.match.HostedMatch},
 * keyed by a room token, and keeps the match alive across a disconnect so a player can reconnect.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li><b>Pairing</b>: first {@code joinbattle} parks WAITING; the second starts the match with
 *       both players' own {@link WebGuiGame} + deck.</li>
 *   <li><b>Reconnect</b>: a {@code joinbattle} with the same {@code (room, uid)} of a running match
 *       rebinds that seat's gui to the new connection ({@code setSink} re-pushes the full state) and
 *       cancels the grace timer.</li>
 *   <li><b>Disconnect grace</b>: a WS close during a running match pauses that seat (no concede yet)
 *       and starts a {@value #GRACE_SECS}s timer; the opponent is told. If the timer expires without a
 *       reconnect, the disconnected player loses ({@link #endMatch}).</li>
 *   <li><b>Surrender / leave</b>: {@code surrender}/{@code leaveroom} ends the match immediately with
 *       the other player as winner.</li>
 * </ul>
 * All state transitions hold the class lock; timers are rare and short.
 */
public final class BattleLobby {
    private BattleLobby() {}

    /** Seconds a disconnected player has to reconnect before losing. */
    public static final int GRACE_SECS = 60;
    private static final int SEATS = 2;

    /** One player in a battle room. */
    public static final class Seat {
        public final String uid;
        public final String name;
        public final Deck deck;
        public final WebGuiGame gui;
        WebGuiGame.Sink sink;
        volatile boolean disconnected;
        volatile ScheduledFuture<?> grace;
        Seat(String uid, String name, Deck deck, WebGuiGame gui, WebGuiGame.Sink sink) {
            this.uid = uid; this.name = name; this.deck = deck; this.gui = gui; this.sink = sink;
        }
    }

    private static final class Room {
        final List<Seat> seats = new ArrayList<>(SEATS);
        boolean started;
        boolean over;
        Seat seatOf(String uid) {
            for (Seat s : seats) if (s.uid != null && s.uid.equals(uid)) return s;
            return null;
        }
        Seat other(Seat s) {
            for (Seat o : seats) if (o != s) return o;
            return null;
        }
    }

    /** Outcome of a join, plus the gui the connection should route its actions to. */
    public static final class Result {
        public enum Kind { WAITING, STARTED, RECONNECTED, FULL }
        public final Kind kind;
        public final WebGuiGame gui;
        Result(Kind kind, WebGuiGame gui) { this.kind = kind; this.gui = gui; }
    }

    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "battle-grace"); t.setDaemon(true); return t;
            });

    /**
     * Join (or rejoin) a battle room. {@code sink} sends frames to this connection; a fresh
     * {@link WebGuiGame} is created for a new seat (so the caller routes actions to {@code result.gui}).
     */
    public static synchronized Result join(String room, String uid, String name, Deck deck, WebGuiGame.Sink sink) {
        Room r = ROOMS.computeIfAbsent(room, k -> new Room());

        if (r.started) {
            Seat seat = uid != null ? r.seatOf(uid) : null;
            if (seat == null || r.over) return new Result(Result.Kind.FULL, null);
            // Reconnect: rebind this seat's gui to the new connection; setSink re-pushes state.
            cancelGrace(seat);
            seat.disconnected = false;
            seat.sink = sink;
            seat.gui.setSink(sink);
            notify(r.other(seat), "opponent_reconnected", "对手已重连", 0);
            return new Result(Result.Kind.RECONNECTED, seat.gui);
        }

        // Pending. A rejoin before the match starts just rebinds the existing seat.
        Seat existing = uid != null ? r.seatOf(uid) : null;
        if (existing != null) {
            existing.sink = sink;
            existing.gui.setSink(sink);
            return new Result(Result.Kind.WAITING, existing.gui);
        }
        if (r.seats.size() >= SEATS) return new Result(Result.Kind.FULL, null);

        WebGuiGame gui = new WebGuiGame();
        gui.setSink(sink);
        Seat seat = new Seat(uid, name, deck, gui, sink);
        r.seats.add(seat);
        if (r.seats.size() >= SEATS) {
            r.started = true;
            MatchBootstrap.startHumanVsHuman(new ArrayList<>(r.seats));
            return new Result(Result.Kind.STARTED, gui);
        }
        return new Result(Result.Kind.WAITING, gui);
    }

    /** A connection closed. Pending seat -> dropped; running seat -> paused with a grace timer. */
    public static synchronized void onDisconnect(String room, String uid) {
        if (room == null) return;
        Room r = ROOMS.get(room);
        if (r == null || r.over) return;
        Seat seat = uid != null ? r.seatOf(uid) : null;
        if (!r.started) {
            if (seat != null) r.seats.remove(seat);
            if (r.seats.isEmpty()) ROOMS.remove(room);
            return;
        }
        if (seat == null || seat.disconnected) return;
        seat.disconnected = true;
        seat.gui.setSink(null);   // pause sends to the dead connection (keep the match alive)
        notify(r.other(seat), "opponent_disconnected", "对手掉线,等待重连…", GRACE_SECS);
        final String rm = room;
        seat.grace = SCHED.schedule(() -> {
            synchronized (BattleLobby.class) {
                Room rr = ROOMS.get(rm);
                if (rr != null && !rr.over && seat.disconnected) {
                    endMatch(rm, seat, "对手掉线超时未重连,判负");
                }
            }
        }, GRACE_SECS, TimeUnit.SECONDS);
    }

    /** Player surrenders or leaves the room: the other player wins, the match ends now. */
    public static synchronized void surrender(String room, String uid, String reason) {
        Room r = room != null ? ROOMS.get(room) : null;
        if (r == null || r.over) return;
        Seat seat = uid != null ? r.seatOf(uid) : null;
        if (seat == null) return;
        endMatch(room, seat, reason != null ? reason : "对手离开,本场结束");
    }

    // ---- internals (call with lock held) ----

    private static void endMatch(String room, Seat loser, String reason) {
        Room r = ROOMS.get(room);
        if (r == null || r.over) return;
        r.over = true;
        Seat winner = r.other(loser);
        // Tell each side the terminal result BEFORE tearing the guis down (stop() drops the sink).
        if (winner != null) notify(winner, "opponent_left", reason, 0, true, true);
        notify(loser, "you_left", reason, 0, true, false);
        for (Seat s : r.seats) { cancelGrace(s); try { s.gui.stop(); } catch (Exception ignored) {} }
        ROOMS.remove(room);
    }

    private static void cancelGrace(Seat s) {
        ScheduledFuture<?> g = s.grace;
        if (g != null) { g.cancel(false); s.grace = null; }
    }

    private static void notify(Seat s, String status, String prompt, int graceSecs) {
        notify(s, status, prompt, graceSecs, false, false);
    }

    private static void notify(Seat s, String status, String prompt, int graceSecs, boolean terminal, boolean win) {
        if (s == null || s.sink == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("prompt", prompt);
        if (graceSecs > 0) m.put("graceSecs", graceSecs);
        if (terminal) { m.put("matchOver", true); m.put("win", win); }
        try { s.sink.onState(Json.write(m)); } catch (Exception ignored) {}
    }
}
