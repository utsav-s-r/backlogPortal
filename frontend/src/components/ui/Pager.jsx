import { ChevronLeft, ChevronRight } from "lucide-react";

// Prev/next pager over a Spring `Page` envelope ({number, totalPages, totalElements}; `number` is
// 0-based). `onGo(pageIndex)` re-fetches — the loader reads the current filters from state, so this
// never carries them.
//
// Replaced three copies (2026-08-31): ManageTab and StudentsManageTab held BYTE-IDENTICAL local
// `Pager` functions, and ClaimStudentsTab inlined the same markup again.
//
// `dataCy` is separate from `noun` because the two disagree in ClaimStudentsTab: it paginates
// students but its spec selects `claim-prev`/`claim-next`. Deriving the hook from the display noun
// would have renamed those, and it would also have collided with StudentsManageTab's own
// `students-prev` — harmless today only because the two live on different tabs.
//
// NOT used by AdminPage, deliberately: its pager is a different presentation (no bordered box, the
// page-info line sits outside it, rotated arrows rather than chevrons). Folding it in here would be
// a visual change, not a consolidation.
// Margin stays with the caller via `className` — it is context, not identity, the same split
// AlertBanner and BrandHeader use (ClaimStudentsTab needs `mt-3`, the two card lists do not).
function Pager({ pageInfo, busy, onGo, noun, dataCy = noun, className = "" }) {
  const { number, totalPages, totalElements } = pageInfo;

  // A single page needs no pager. Decided here rather than at each call site, where it was the one
  // piece of pager behaviour the extraction left duplicated three times.
  if (totalPages <= 1) return null;

  return (
    <div
      className={[
        "flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-stroke bg-surface-muted px-4 py-3 text-sm",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      data-cy={`${dataCy}-pager`}
    >
      <span className="text-ink-muted">
        Page {number + 1} of {totalPages} · {totalElements} {noun}
      </span>
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => onGo(number - 1)}
          disabled={busy || number <= 0}
          data-cy={`${dataCy}-prev`}
          className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary disabled:opacity-40"
        >
          <ChevronLeft size={13} /> Prev
        </button>
        <button
          type="button"
          onClick={() => onGo(number + 1)}
          disabled={busy || number >= totalPages - 1}
          data-cy={`${dataCy}-next`}
          className="inline-flex items-center gap-1 rounded-lg border border-stroke px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary disabled:opacity-40"
        >
          Next <ChevronRight size={13} />
        </button>
      </div>
    </div>
  );
}

export default Pager;
