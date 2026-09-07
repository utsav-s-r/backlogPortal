import { useEffect, useRef } from "react";
import { History, LoaderCircle, X } from "lucide-react";
import { outcomeBadgeClass } from "./outcomeBadge";

// Tab-trap descendants. [tabindex="-1"] excluded on purpose: it means script-focusable, not
// Tab-focusable.
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The registration audit trail, as a modal. State and the fetch live in useRegistrationHistory,
 * called by the page — see that file for why the cache cannot live in here.
 *
 * Three things history-modal-focus.cy.js pins, none of which may drift:
 *
 *  1. The keydown listener is on `document`, NOT on the dialog. One of its tests focuses
 *     `[data-cy="admin-search"]` — an element in the FILTER PANEL — then presses Tab and expects
 *     focus pulled back in. A dialog-scoped listener never sees that event.
 *  2. The dialog has exactly ONE focusable descendant (the close button), which is why that spec
 *     omits a wrap-DESTINATION assertion as trivially true. Adding a second focusable element here
 *     silently changes what that spec means.
 *  3. `e.preventDefault()` is the real assertion. Cypress has no native Tab, so a synthetic one
 *     moves nothing by itself and "focus stayed inside" passes even with the trap removed.
 *     `defaultPrevented` is what proves interception.
 *
 * The trap is not polish: aria-modal="true" TELLS assistive tech the rest of the page is inert, so
 * letting Tab reach it makes the markup a lie.
 */
function RegistrationHistoryDialog({ events, loading, error, onClose }) {
  const closeRef = useRef(null); // to move focus INTO the dialog on open
  const dialogRef = useRef(null); // the trap needs the dialog's focusable descendants

  // onClose is deliberately NOT in the deps: it is a plain function, so it changes identity every
  // render and would re-register the listener each time. It only sets state and focuses a ref, so
  // a stale closure is harmless.
  useEffect(() => {
    const onKeyDown = (e) => {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      // getClientRects() over offsetParent: the dialog sits inside a fixed overlay, where
      // offsetParent is an unreliable visibility test
      const items = [...dialog.querySelectorAll(FOCUSABLE_SELECTOR)].filter(
        (el) => el.getClientRects().length > 0,
      );
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      // the !contains arm matters: focus can already be outside (a click on the backdrop, or a
      // browser that moved it), and without it Tab would keep walking the page behind
      if (
        e.shiftKey &&
        (document.activeElement === first || !dialog.contains(document.activeElement))
      ) {
        e.preventDefault();
        last.focus();
      } else if (
        !e.shiftKey &&
        (document.activeElement === last || !dialog.contains(document.activeElement))
      ) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    closeRef.current?.focus();
    return () => document.removeEventListener("keydown", onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}
      role="presentation"
    >
      <div
        ref={dialogRef}
        /* Capped, not a plain box: the event list is unbounded, and an overflowing item in an
           `items-center` FIXED overlay is clipped at BOTH ends with no scrollbar. `svh` (not
           vh/dvh) is the smallest viewport, so it fits whatever the mobile URL bar is doing. */
        className="flex max-h-[90svh] w-full max-w-lg flex-col rounded-2xl bg-surface-1 p-6 shadow-soft"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="history-dialog-title"
        data-cy="history-dialog"
      >
        <div className="mb-4 flex shrink-0 items-center justify-between">
          <h3
            id="history-dialog-title"
            className="inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink"
          >
            <History size={18} /> Registration History
          </h3>
          <button
            type="button"
            ref={closeRef}
            onClick={onClose}
            data-cy="history-close"
            className="rounded-lg p-1.5 text-ink-muted transition-colors hover:bg-surface-muted"
            aria-label="Close history"
          >
            <X size={18} />
          </button>
        </div>

        {/* min-h-0 is load-bearing: a flex item defaults to min-height:auto and won't shrink below
            its content, so overflow-y-auto would never engage. A bare div matches nothing in
            FOCUSABLE_SELECTOR, so the one-focusable-descendant invariant above holds. */}
        <div className="min-h-0 overflow-y-auto">
          {loading ? (
            <p className="inline-flex items-center gap-2 text-sm text-ink">
              <LoaderCircle size={16} className="animate-spin" /> Loading history...
            </p>
          ) : error ? (
            <p className="text-sm font-medium text-alert" role="alert" data-cy="admin-history-error">
              {error}
            </p>
          ) : events.length === 0 ? (
            <p className="text-sm text-ink-muted">No history recorded for this registration.</p>
          ) : (
            <ol className="space-y-3">
              {events.map((ev, i) => (
                <li
                  key={i}
                  className="flex items-start gap-3 rounded-lg bg-surface-muted px-3 py-2.5"
                >
                  <span
                    className={`mt-0.5 rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${outcomeBadgeClass(
                      ev.action,
                      "bg-surface-1",
                    )}`}
                  >
                    {ev.action}
                  </span>
                  <div className="text-sm">
                    <p className="text-ink">
                      {ev.actor || "unknown"}
                      <span className="text-ink-muted"> ({ev.actorRole})</span>
                    </p>
                    <p className="text-xs text-ink-muted">
                      {ev.timestamp ? new Date(ev.timestamp).toLocaleString() : ""}
                    </p>
                    {ev.note && <p className="mt-1 text-xs text-ink">{ev.note}</p>}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>
      </div>
    </div>
  );
}

export default RegistrationHistoryDialog;
