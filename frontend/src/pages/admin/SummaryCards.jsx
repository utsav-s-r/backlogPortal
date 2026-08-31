import { CircleDashed, Shield, Users, XCircle } from "lucide-react";
import AlertBanner from "../../components/AlertBanner";

// The four stat cards above the registrations table. Counts come from the server's summary-counts
// endpoint and span every status of the FILTERED set (the list's filters minus the status tab), so
// they do not change as you page or switch tabs.
//
// The label <p> and the value <p> must stay DIRECT SIBLINGS under the card div:
// admin-verification.cy.js asserts `cy.contains("Total").parent().should("contain", "—")`, which
// walks exactly one level up. Wrapping the label — the obvious way to lay out the icon — moves the
// value out of `.parent()` and breaks that test. The icon therefore lives INSIDE the label <p>,
// which is how the four hand-written copies already had it.
const CARDS = [
  { key: "total", label: "Total", Icon: Users, valueClass: "text-secondary-ink" },
  { key: "submitted", label: "Pending", Icon: CircleDashed, valueClass: "text-secondary-ink" },
  { key: "verified", label: "Verified", Icon: Shield, valueClass: "text-secondary-ink" },
  { key: "rejected", label: "Rejected", Icon: XCircle, valueClass: "text-red-600" },
];

/**
 * @param counts {{total,submitted,verified,rejected}} server totals
 * @param error   message when the counts fetch failed — every card then reads an em-dash
 */
function SummaryCards({ counts, error }) {
  // A failed counts fetch must not render as a number. Four confident zeros above a table full of
  // rows claims "the queue is empty", the one thing these cards must never say on their own
  // failure; stale pre-action numbers are just as wrong, so the post-action refetch routes here too.
  const statValue = (n) => (error ? "—" : n);

  return (
    <>
      <section className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        {/* `card.Icon`, not a destructured `Icon`: eslint-plugin-react is not enabled, so JSX use
            of a destructured component reads as an unused variable and takes lint off its known 9.
            AdminLoginPage's role cards do the same for the same reason. */}
        {CARDS.map((card) => (
          <div
            key={card.key}
            className="rounded-2xl border border-stroke bg-surface-1 p-4 shadow-soft"
          >
            <p className="inline-flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.1em] text-ink">
              <card.Icon size={13} /> {card.label}
            </p>
            <p className={`mt-1 text-3xl font-semibold ${card.valueClass}`}>
              {statValue(counts[card.key])}
            </p>
          </div>
        ))}
      </section>

      {error && (
        <AlertBanner tone="warning" role="status" data-cy="admin-counts-error" className="mb-6">
          Totals unavailable — {error} The table below is unaffected.
        </AlertBanner>
      )}
    </>
  );
}

export default SummaryCards;
