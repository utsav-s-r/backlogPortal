/**
 * VERIFIED/REJECTED/other pill classes, shared by the table rows and the history dialog. Only the
 * neutral fallback differs — a row sits on the card, an event on a muted panel — so the caller
 * passes it rather than the two copies drifting apart.
 *
 * Own module, not a helper inside a .jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 */
export function outcomeBadgeClass(outcome, neutralBg) {
  if (outcome === "VERIFIED") return "bg-primary-tint text-primary-ink";
  if (outcome === "REJECTED") return "bg-red-50 text-red-600";
  return `${neutralBg} text-secondary-ink`;
}
