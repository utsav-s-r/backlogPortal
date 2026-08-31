// A labelled form control — label above, control below, one flex column. Replaced 60 hand-typed
// copies (2026-08-31) of the same div/label/control sandwich.
//
// THE LABEL WRAPS THE CONTROL, rather than sitting beside it as a sibling. That is the reason this
// is a component and not just another class constant in lib/formClasses: 25 of the 60 copies had
// no `htmlFor`, so their label was associated with nothing — clicking it did nothing, and a screen
// reader announced the control unlabelled. Wrapping associates implicitly, with no id to keep in
// sync, so those 25 are fixed structurally and the gap cannot reopen one field at a time.
//
// Checked before choosing this shape — the two cases where wrapping misbehaves are a second
// interactive element (the label would steal its clicks) and more than one control (the
// association becomes ambiguous). Neither occurs: all 60 blocks held exactly one control and none
// contained a button or anchor. Re-check that if a caller ever puts a second control in here.
//
// `htmlFor` is still forwarded where the original had it. Explicit and implicit association
// coexist happily, and keeping it holds the DOM delta down to div->label and label->span — the
// classes on both boxes are unchanged, so nothing about the rendering moves.
//
// Layout (grid spans, margins) stays with the caller via `className` — it is context, not
// identity, the same split BrandHeader and AlertBanner use. `labelClassName` carries the handful of
// labels that add `text-ink` or `text-ink-muted`.

import { FIELD_LABEL } from "../../lib/formClasses";

function Field({ label, htmlFor, className = "", labelClassName = "", children }) {
  const wrapper = ["flex flex-col gap-1.5", className].filter(Boolean).join(" ");
  const labelClasses = [FIELD_LABEL, labelClassName].filter(Boolean).join(" ");

  return (
    <label htmlFor={htmlFor} className={wrapper}>
      <span className={labelClasses}>{label}</span>
      {children}
    </label>
  );
}

export default Field;
