import { useTheme } from "../../context/ThemeContext";

// Decision 13: the WORDS "Dark Mode" next to a plain sliding toggle. That is the whole design —
// no sun/moon, no icon swapping, no segmented track, no three-state system option. A word needs no
// legend and no hover tooltip; an icon needs both.
//
// Every caller renders it on a navy ground (the sidebar rail, the student/public top band), so the
// track's fills are white alphas and the on-state is `bg-cta`. Fixed white-on-navy in both themes,
// exactly like HeaderPill — that is why no [data-theme="dark"] counterpart exists or is needed.
//
// `label` is a node, not a boolean, because the collapsed sidebar rail needs the SAME words to come
// back as its hover tooltip rather than disappearing. Pass null for no label at all; the switch is
// still operable, so nothing in the rail becomes decoration.
// The default shell is the top band's pill (a fill, a different fill on hover, no border). The
// sidebar passes its own NAV_ITEM row instead.
const BAND_PILL =
  "inline-flex items-center gap-1.5 rounded-lg bg-white/20 px-3 py-2 text-sm font-semibold " +
  "text-white transition-colors hover:bg-white/30";

export default function ThemeToggle({
  className = BAND_PILL,
  label = <span>Dark Mode</span>,
}) {
  const { isDark, toggleTheme } = useTheme();

  return (
    <button
      type="button"
      role="switch"
      aria-checked={isDark}
      aria-label="Dark Mode"
      onClick={toggleTheme}
      className={className}
    >
      {label}
      <span
        className={`relative h-5 w-9 shrink-0 rounded-full transition-colors ${
          isDark ? "bg-cta" : "bg-white/30"
        }`}
      >
        <span
          className={`absolute top-0.5 size-4 rounded-full bg-white transition-all ${
            isDark ? "left-4.5" : "left-0.5"
          }`}
        />
      </span>
    </button>
  );
}
