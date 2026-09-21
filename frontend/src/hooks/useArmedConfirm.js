import { useState } from "react";

/**
 * The two-step arm -> confirm control used for a destructive action that happens IN PLACE, inside a
 * row: the first click swaps the button for Confirm/Cancel, the second click acts.
 *
 * Deliberately NOT applied to the four `window.confirm` sites (subject delete, user delete, student
 * delete/unassign, proctor unassign). Those are a different pattern on purpose: a native modal for
 * an action whose consequences need a sentence of explanation, where this inline swap has room for
 * two words. Converting them either way is a UX decision, not a consolidation.
 *
 * `arm` takes the row's id; ids are compared with `===`, so they must be the same type the rows
 * carry (a string regId, a numeric department id — both work, neither is coerced).
 */
export function useArmedConfirm() {
  const [armedId, setArmedId] = useState(null);

  return {
    /** True only for the row currently armed. Never true for a null/undefined id. */
    isArmed: (id) => id != null && armedId === id,
    arm: (id) => setArmedId(id),
    disarm: () => setArmedId(null),
  };
}
