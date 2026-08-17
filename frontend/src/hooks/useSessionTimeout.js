import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { SESSION_SCOPES, sessionExpiryMs } from "../lib/session";

// Start warning this long before the session ends.
const WARN_BEFORE_MS = 10 * 60 * 1000;
// How often the countdown is recomputed. 30s so a "5 minutes left" banner is never more than
// half a minute stale, without re-rendering the whole page every second.
const TICK_MS = 30 * 1000;

/** Whole minutes left once inside the warning window, else null. Never returns 0: at <60s left
 *  it says "1 minute" rather than a zero that reads like the session already ended. */
function warningMinutesLeft(scope) {
  const expiresAt = sessionExpiryMs(scope);
  if (!expiresAt) return null;
  const remaining = expiresAt - Date.now();
  if (remaining <= 0 || remaining > WARN_BEFORE_MS) return null;
  return Math.max(1, Math.ceil(remaining / 60000));
}

/**
 * Signs the user out when the fixed 1h session ends, and reports the minutes left once inside the
 * final 10 so the caller can warn. One timer plus one slow interval: no activity tracking and no
 * renewal — the session length is decided at login and never moves.
 *
 * The sign-out here is CONVENIENCE ONLY. The real cap is the JWT's own expiry, which the server
 * re-checks on every request; past it every call is 401 no matter what this timer does (a slept
 * laptop, a closed tab, a tampered clock). Its job is to stop an idle tab from looking signed in,
 * and to land the user on the login screen with an explanation.
 *
 * @param {"admin"|"student"} scope
 * @returns {number|null} whole minutes remaining while in the warning window, else null
 */
export function useSessionTimeout(scope) {
  const navigate = useNavigate();
  // Seeded during render, not in an effect, so a tab opened inside the last 10 minutes shows the
  // banner on first paint (and so this doesn't become a set-state-in-effect).
  const [minutesLeft, setMinutesLeft] = useState(() => warningMinutesLeft(scope));

  useEffect(() => {
    const cfg = SESSION_SCOPES[scope];
    if (!cfg) return undefined;

    const expiresAt = sessionExpiryMs(scope);
    if (!expiresAt) return undefined; // no active session in this tab — nothing to time out

    // Already past expiry (e.g. the tab was asleep) fires immediately at 0.
    const signOutTimer = setTimeout(() => {
      cfg.clear();
      navigate(`${cfg.loginPath}?expired=1`, { replace: true });
    }, Math.max(expiresAt - Date.now(), 0));

    const tick = setInterval(() => setMinutesLeft(warningMinutesLeft(scope)), TICK_MS);

    return () => {
      clearTimeout(signOutTimer);
      clearInterval(tick);
    };
  }, [scope, navigate]);

  return minutesLeft;
}
