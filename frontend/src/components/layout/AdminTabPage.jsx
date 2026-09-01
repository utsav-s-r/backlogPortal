import { useState, useEffect } from "react";
import { useSearchParams } from "react-router-dom";
import AdminPageShell from "./AdminPageShell";
import AlertBanner from "../AlertBanner";
import api from "../../lib/api";
import { reportLoadError } from "../../lib/loadError";
import { findOwnDepartment } from "../../lib/session";
import { useRoleGuard } from "../../hooks/useRoleGuard";

// The shell behind an admin section that is one route with tabs — the subject catalog and student
// management. It owns the role guard, the single /departments fetch, pin resolution, the ?tab=
// whitelist and the tab bar. Don't hand-roll a third copy. The page chrome around it (tinted page,
// container, navy header, Dashboard pill) is AdminPageShell, shared with the non-tabbed admin pages.
//
// Three details are settled here on purpose: `pinnedDeptId` is a plain expression, NOT a useMemo
// (react-hooks' preserve-manual-memoization rule errors on that useMemo once role-dependent tab
// derivation joins the same render — lint, not runtime); the deptError banner sits directly above
// the tab CONTENT, with what it degrades; `heading` is optional, so a page without one renders no
// heading markup at all.
//
// Auth is NOT enforced here. `allowedRoles` only decides whether to render or bounce to /admin;
// every /api/admin/** call is authorized server-side per endpoint. Never let this be the reason an
// endpoint skips its @PreAuthorize.
function AdminTabPage({
  allowedRoles,
  deptPinnedRoles,
  tabs,
  ariaLabel,
  // A render prop, like `children`: it receives the same `shared` bundle so a role- or
  // department-dependent heading cannot re-derive (and drift from) what this shell already
  // computed. Optional — the subject catalog has no heading.
  heading = null,
  children,
}) {
  const [searchParams, setSearchParams] = useSearchParams();
  const allowed = useRoleGuard(allowedRoles);

  const adminRole = sessionStorage.getItem("adminRole") || "";
  const adminDepartment = sessionStorage.getItem("adminDepartment") || "";
  const deptLocked = deptPinnedRoles.includes(adminRole);

  const [departments, setDepartments] = useState([]);
  const [deptError, setDeptError] = useState("");

  const tabKeys = new Set(tabs.map((t) => t.key));

  // Dept-scoped roles are pinned to their own department across every tab: pure derivation from
  // (departments, role, dept), computed during render rather than stored via an effect, so it
  // resolves on first paint with no cascading re-render.
  const pinnedDept =
    deptLocked && adminDepartment && departments.length > 0
      ? findOwnDepartment(departments, adminDepartment)
      : null;
  const pinnedDeptId = pinnedDept ? String(pinnedDept.id) : "";

  // whitelist the query param so a hand-edited ?tab= cannot render nothing; default "manage"
  const tabParam = searchParams.get("tab");
  const activeTab = tabKeys.has(tabParam) ? tabParam : "manage";
  const setActiveTab = (key) => setSearchParams({ tab: key }, { replace: true });

  // role="tab" without a real tab -> panel relationship announces "tab, 1 of 3" and leaves a screen
  // reader no route to the panel (WCAG 4.1.2). Only the active panel is mounted, so every tab points
  // aria-controls at the ONE panel container; per-tab ids would dangle for the unrendered tabs.
  const PANEL_ID = "admin-tab-panel";
  const tabId = (key) => `admin-tab-${key}`;

  // AUTOMATIC activation (APG's default) is correct ONLY because switching a tab fires no request —
  // ManageTab has no mount effect. A fetch-on-mount tab would need manual activation instead.
  // Focus moves by id: buttons are keyed by t.key, so React reuses the node and focus survives the
  // re-render.
  const onTabKeyDown = (e) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[e.key];
    const i = tabs.findIndex((t) => t.key === activeTab);
    let next = null;
    if (step !== undefined) next = (i + step + tabs.length) % tabs.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = tabs.length - 1;
    if (next === null) return;
    e.preventDefault();
    setActiveTab(tabs[next].key);
    document.getElementById(tabId(tabs[next].key))?.focus();
  };

  useEffect(() => {
    if (!allowed) return;
    api
      .get("/departments")
      .then((res) => {
        setDepartments(res.data);
        setDeptError("");
      })
      // a swallowed failure here silently un-pins a dept-scoped user's department
      .catch((err) =>
        reportLoadError(
          err,
          setDeptError,
          "Could not load departments. Some filters may be unavailable — refresh to retry.",
        ),
      );
  }, [allowed]);

  // What every tab needs and none of them should re-derive.
  const shared = { departments, adminRole, adminDepartment, deptLocked, pinnedDeptId };

  if (!allowed) return null;

  return (
    <AdminPageShell containerClassName="max-w-5xl pb-8">
      {heading?.({ shared })}

      <div
        className="mb-6 flex flex-wrap gap-2"
        role="tablist"
        aria-label={ariaLabel}
        onKeyDown={onTabKeyDown}
      >
        {tabs.map((t) => {
          const Icon = t.icon;
          const on = activeTab === t.key;
          return (
            <button
              key={t.key}
              id={tabId(t.key)}
              type="button"
              role="tab"
              aria-selected={on}
              aria-controls={PANEL_ID}
              // roving tabindex: Tab reaches the tablist once, landing on the SELECTED tab;
              // Left/Right move within it
              tabIndex={on ? 0 : -1}
              onClick={() => setActiveTab(t.key)}
              data-cy={`tab-${t.key}`}
              className={`inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-sm font-semibold transition-colors ${
                on
                  ? "border-primary bg-primary-tint text-primary-ink"
                  : "border-stroke bg-surface-muted text-ink hover:border-primary"
              }`}
            >
              <Icon size={15} /> {t.label}
            </button>
          );
        })}
      </div>

      {deptError ? (
        <AlertBanner tone="error" role="alert" data-cy="dept-load-error" className="mb-3">
          {deptError}
        </AlertBanner>
      ) : null}

      {/* No tabIndex: these panels hold focusable controls, so a stop here would land on nothing. */}
      <div id={PANEL_ID} role="tabpanel" aria-labelledby={tabId(activeTab)}>
        {children({ activeTab, shared })}
      </div>
    </AdminPageShell>
  );
}

export default AdminTabPage;
