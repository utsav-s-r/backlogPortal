import { useRef, useState } from "react";
import api from "../../lib/api";

/**
 * The audit trail behind the registrations table's History button.
 *
 * `registration_events` is append-only and this admin is the only writer reachable from that
 * screen, so a fetched trail stays valid until we verify/reject that row — hence the per-regId
 * cache, which makes reopening instant. **The cache must outlive the dialog**, so this hook is
 * called from the PAGE, not from inside RegistrationHistoryDialog: a dialog that unmounts on close
 * would take the cache with it and silently delete the optimisation. `invalidate(regId)` is what
 * the post-verify/reject resync calls.
 *
 * The trigger element is captured HERE, at open time, rather than on the dialog's mount:
 * `document.activeElement` is still the History button when the click handler runs, and
 * history-modal-focus.cy.js asserts Escape returns focus to exactly that button.
 */
export function useRegistrationHistory() {
  const [regId, setRegId] = useState("");
  const [cache, setCache] = useState({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  // regId the in-flight request belongs to, so a superseded response cannot clear the spinner or
  // post an error over a row the admin has since switched to.
  const reqRef = useRef("");
  // the opener, so focus returns there on close rather than to the top of the document
  const triggerRef = useRef(null);

  const open = async (nextId) => {
    triggerRef.current = document.activeElement;
    setRegId(nextId);
    setError("");
    reqRef.current = nextId;
    if (cache[nextId]) {
      setLoading(false);
      return;
    }

    setLoading(true);
    try {
      const res = await api.get(`/admin/registrations/${nextId}/events`);
      setCache((prev) => ({ ...prev, [nextId]: Array.isArray(res.data) ? res.data : [] }));
    } catch (err) {
      // Surface it: a failed fetch and a genuinely empty trail render identically otherwise, so a
      // 403/404/network drop would read as "nobody actioned this registration" — the one claim an
      // audit view must never make on its own failure. Nothing is cached, so reopening retries.
      console.error("Failed to load history", err);
      if (reqRef.current === nextId) {
        setError(err.response?.data?.message || "Unable to load history. Please try again.");
      }
    } finally {
      if (reqRef.current === nextId) setLoading(false);
    }
  };

  const close = () => {
    setRegId("");
    triggerRef.current?.focus(); // back to the History button that opened it
  };

  /** Drop one row's cached trail — verify/reject appends an event, so it is stale. Returns `prev`
   *  untouched when absent, so React can bail out of the re-render. */
  const invalidate = (id) =>
    setCache((prev) => {
      if (!prev[id]) return prev;
      const next = { ...prev };
      delete next[id];
      return next;
    });

  return { regId, events: cache[regId] || [], loading, error, open, close, invalidate };
}
