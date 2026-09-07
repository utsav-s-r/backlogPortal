/**
 * VERIFIED/REJECTED/other pill classes, shared by the table rows and the history dialog. Only the
 * neutral fallback differs — a row sits on the card, an event on a muted panel — so the caller
 * passes it, so the two call sites cannot drift apart.
 *
 * Own module, not a helper inside a .jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 */
export function outcomeBadgeClass(outcome, neutralBg) {
  // These two returned IDENTICAL classes until 2026-09-08, so a verified registration and a rejected
  // one were indistinguishable in the table and in the history dialog. Opposite outcomes need
  // opposite colours; that is the whole reason the palette carries a success colour at all.
  if (outcome === "VERIFIED") return "bg-success-tint text-success";
  if (outcome === "REJECTED") return "bg-alert-tint text-alert";
  return `${neutralBg} text-secondary-ink`;
}
