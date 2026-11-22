package com.ruoyi.framework.security;

import org.apache.shiro.session.Session;

/** Tracks human interaction independently of Shiro's last HTTP access time. */
public final class DrillingSessionActivity {
    private static final String LAST_INPUT = "drilling.lastUserInteraction";
    public static final String PATH = "/api/access/me/activity";

    private DrillingSessionActivity() {}

    public static void start(Session session, long timeout, long now) {
        session.setTimeout(timeout);
        session.setAttribute(LAST_INPUT, now);
    }

    public static long lastInput(Session session) {
        Object stored = session.getAttribute(LAST_INPUT);
        return stored instanceof Long value ? value : session.getStartTimestamp().getTime();
    }

    public static boolean expired(Session session, long timeout, long now) {
        return now - lastInput(session) >= timeout;
    }

    public static boolean report(Session session, long now, long ageMillis) {
        long previous = lastInput(session);
        if (now - previous >= session.getTimeout()) return false;
        // The browser may coalesce consecutive input events. An offset can only shorten a renewal.
        long observed = now - Math.max(0, ageMillis);
        if (observed > previous) session.setAttribute(LAST_INPUT, observed);
        return true;
    }
}
