import { ChevronLeft, ChevronRight } from "lucide-react";
import { btn } from "../../lib/buttonClasses";

// Prev/next pager over a Spring `Page` ({number 0-based, totalPages, totalElements}).
// `onGo(pageIndex)` re-fetches; the loader reads filters from state, so this never carries them.
// `className` margin stays with the caller (ClaimStudentsTab `mt-3`, card lists none).
//
// `dataCy` ≠ `noun`: ClaimStudentsTab paginates "students" but hooks `claim-*`. Deriving from the
// noun would collide with StudentsManageTab's `students-prev` — harmless only, today, because they
// sit on different tabs.
//
// ⚠️ UNTESTED — every paginated stub returns totalPages 0|1 and the gate below is <= 1, so this
// never renders in a spec and none of its 9 derived hooks (3 × 3 call sites) is referenced.
// Needs a totalPages >= 2 stub; until then changes here are unguarded — check all 3 call sites.
//
// NOT AdminPage's pager: different presentation (no bordered box, page-info outside, rotated
// arrows). Folding it in is a visual change, not a consolidation.
function Pager({ pageInfo, busy, onGo, noun, dataCy = noun, className = "" }) {
  const { number, totalPages, totalElements } = pageInfo;

  // A single page needs no pager. Decided here, not at each call site.
  if (totalPages <= 1) return null;

  return (
    <div
      className={[
        "flex flex-wrap items-center justify-between gap-3 rounded-lg bg-surface-muted px-4 py-3 text-sm",
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
          className={btn("neutral", "sm")}
        >
          <ChevronLeft size={13} /> Prev
        </button>
        <button
          type="button"
          onClick={() => onGo(number + 1)}
          disabled={busy || number >= totalPages - 1}
          data-cy={`${dataCy}-next`}
          className={btn("neutral", "sm")}
        >
          Next <ChevronRight size={13} />
        </button>
      </div>
    </div>
  );
}

export default Pager;
