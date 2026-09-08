import { useTheme } from "../../context/ThemeContext";
import { btn } from "../../lib/buttonClasses";

// Decision 13: the WORDS "Dark Mode" next to a plain sliding toggle. That is the whole design —
// no sun/moon, no icon swapping, no segmented track, no three-state system option. A word needs no
// legend and no hover tooltip; an icon needs both.
//
// The track fills with the accent when on and sits on `bg-stroke` when off; the thumb is the page
// surface. All three are theme-aware tokens, so no [data-theme="dark"] counterpart is needed.
//
// `label` is a node, not a boolean, because the collapsed sidebar rail needs the SAME words to come
// back as its hover tooltip rather than disappearing. Pass null for no label at all; the switch is
// still operable, so nothing in the rail becomes decoration.
// The default shell is the top band's pill (a fill, a different fill on hover, no border). The
// sidebar passes its own NAV_ITEM row instead.
const BAND_PILL = btn("quiet");

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
          isDark ? "bg-accent" : "bg-stroke"
        }`}
      >
        <span
          className={`absolute top-0.5 size-4 rounded-full bg-surface-1 transition-all ${
            isDark ? "left-4.5" : "left-0.5"
          }`}
        />
      </span>
    </button>
  );
}
