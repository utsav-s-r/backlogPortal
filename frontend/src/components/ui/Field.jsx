// A labelled form control — label above, control below, one flex column. Every labelled field goes
// through this; don't hand-type the div/label/control sandwich again.
//
// THE LABEL WRAPS THE CONTROL, rather than sitting beside it as a sibling. That is the reason this
// is a component and not just another class constant in lib/formClasses: a hand-written label
// without `htmlFor` is associated with nothing — clicking it does nothing, and a screen reader
// announces the control unlabelled. Wrapping associates implicitly, with no id to keep in sync.
//
// Wrapping is only safe while a Field holds exactly ONE control and no button or anchor: a second
// interactive element would have its clicks stolen by the label, and a second control makes the
// association ambiguous. Re-check that before putting anything else in here.
//
// `htmlFor` is still forwarded — explicit and implicit association coexist happily.
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
