package tw.nekomimi.nekogram.helpers;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Smart (adaptive) download boost for non-Premium accounts.
 * <p>
 * Starts at Nullgram-level parallelism (6MB in flight, 8 connections) and hill-climbs the native in-flight byte budget per account from measured throughput:
 * grows while more data in flight actually helps, steps back once the server-side
 * cap is reached, and drops to the stock budget when throughput collapses
 * (native tgnet retries FLOOD_WAIT / FLOOD_PREMIUM_WAIT internally, so a sudden
 * drop is the only signal visible from Java).
 */
public final class AdaptiveDownloadTuner {

    /** 1MB: the largest part upload.getFile allows; offsets stay 1MB-aligned. */
    public static final int CHUNK_SIZE = 1024 * 1024;
    public static final int MIN_BUDGET = 8 * 1024 * 1024;
    public static final int MAX_BUDGET = 32 * 1024 * 1024;
    public static final int MAX_IN_FLIGHT_REQUESTS = MAX_BUDGET / CHUNK_SIZE;
    /** Must be a power of two. */
    public static final int CONNECTIONS = 8;

    private static final long WINDOW_MS = 2000;
    private static final long IDLE_GAP_MS = 4000;
    private static final double GAIN_THRESHOLD = 1.08;
    private static final double COLLAPSE_RATIO = 0.35;
    private static final long HOLD_MS = 15_000;
    private static final long COOLDOWN_MS = 20_000;
    private static final int STEP = 2 * 1024 * 1024;

    private static final class State {
        int budget = MIN_BUDGET;
        int prevBudget = MIN_BUDGET;
        boolean applied;
        boolean lastStepWasIncrease;
        long windowStart;
        long lastBytesAt;
        long windowBytes;
        double lastRate;
        double avgRate;
        long holdUntil;
    }

    private static final State[] states = new State[32];

    private AdaptiveDownloadTuner() {
    }

    private static State state(int account) {
        if (account < 0 || account >= states.length) {
            account = 0;
        }
        State s = states[account];
        if (s == null) {
            s = new State();
            states[account] = s;
        }
        return s;
    }

    public static synchronized int budget(int account) {
        return state(account).budget;
    }

    /** Push the current budget to native once per process, before the first smart download. */
    public static synchronized void ensureApplied(int account) {
        State s = state(account);
        if (!s.applied) {
            s.applied = true;
            push(account, s.budget);
        }
    }

    /** Called when the user leaves smart mode: hand control back to the stock native budget. */
    public static synchronized void reset() {
        for (int i = 0; i < states.length; i++) {
            if (states[i] != null && states[i].applied) {
                push(i, 0);
            }
            states[i] = null;
        }
    }

    /** Feed every received file part. */
    public static synchronized void onBytes(int account, int bytes) {
        State s = state(account);
        long now = SystemClock.elapsedRealtime();
        if (s.windowStart == 0 || now - s.lastBytesAt > IDLE_GAP_MS) {
            // Fresh start or the queue was idle: don't let the gap read as a throttle.
            s.windowStart = now;
            s.windowBytes = 0;
            s.lastStepWasIncrease = false;
        }
        s.lastBytesAt = now;
        s.windowBytes += bytes;
        long elapsed = now - s.windowStart;
        if (elapsed < WINDOW_MS) {
            return;
        }
        double rate = s.windowBytes / (double) elapsed;
        s.windowStart = now;
        s.windowBytes = 0;
        evaluate(account, s, rate, now);
    }

    private static void evaluate(int account, State s, double rate, long now) {
        if (s.avgRate > 0 && rate < s.avgRate * COLLAPSE_RATIO && s.budget > MIN_BUDGET) {
            setBudget(account, s, MIN_BUDGET, false);
            s.holdUntil = now + COOLDOWN_MS;
            s.avgRate = rate;
            s.lastRate = rate;
            return;
        }
        s.avgRate = s.avgRate == 0 ? rate : s.avgRate * 0.7 + rate * 0.3;

        if (now < s.holdUntil) {
            s.lastRate = rate;
            return;
        }

        if (s.lastStepWasIncrease) {
            if (rate >= s.lastRate * GAIN_THRESHOLD) {
                grow(account, s);
            } else {
                setBudget(account, s, s.prevBudget, false);
                s.holdUntil = now + HOLD_MS;
            }
        } else {
            grow(account, s);
        }
        s.lastRate = rate;
    }

    private static void grow(int account, State s) {
        if (s.budget >= MAX_BUDGET) {
            s.lastStepWasIncrease = false;
            return;
        }
        int next = Math.min(MAX_BUDGET, s.budget * 3 / 2);
        next = Math.max(s.budget + STEP, (next / STEP) * STEP);
        setBudget(account, s, Math.min(MAX_BUDGET, next), true);
    }

    private static void setBudget(int account, State s, int value, boolean increase) {
        s.prevBudget = s.budget;
        s.budget = value;
        s.lastStepWasIncrease = increase;
        push(account, value);
    }

    private static void push(int account, int value) {
        try {
            ConnectionsManager.native_setDownloadBudget(account, value);
        } catch (Throwable ignore) {
        }
    }
}
