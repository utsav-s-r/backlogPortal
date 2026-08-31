import { useState, useEffect } from "react";
import { ArrowLeft } from "lucide-react";
import { Link, useSearchParams } from "react-router-dom";
import BrandHeader from "./BrandHeader";
import AlertBanner from "../AlertBanner";
import api from "../../lib/api";
import { reportLoadError } from "../../lib/loadError";
import { findOwnDepartment } from "../../lib/session";
import { useRoleGuard } from "../../hooks/useRoleGuard";
import HeaderPill from "../ui/HeaderPill";

// The shell behind an admin section that is one route with tabs — the subject catalog and student
// management. Replaced two copies (2026-08-31) that were the same component: identical role guard,
// identical /departments fetch down to the fallback message, identical pin resolution, identical
// ?tab= whitelist, identical page chrome, and a byte-identical 22-line tab bar.
//
// They had already drifted three ways, which is the argument for one copy rather than two:
// `pinnedDeptId` was a useMemo in one and a plain expression in the other, the deptError banner sat
// ABOVE the tab bar in one and BELOW it in the other, and only one had a page heading.
//
// Resolved as: the plain expression (react-hooks' preserve-manual-memoization rule errors on that
// useMemo once role-dependent tab derivation joins the same render — lint, not runtime), the banner
// directly above the tab CONTENT so it sits with what it degrades, and `heading` as an optional
// prop so the page that has none renders exactly the DOM it did before.
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
    <div className="min-h-screen bg-surface-1 px-4 py-8 text-ink sm:px-6 lg:px-8">
      <div className="mx-auto w-full max-w-5xl pb-8">
        <BrandHeader className="mb-6">
          <HeaderPill as={Link} to="/admin">
            <ArrowLeft size={15} /> Dashboard
          </HeaderPill>
        </BrandHeader>

        {heading?.({ shared })}

        <div className="mb-6 flex flex-wrap gap-2" role="tablist" aria-label={ariaLabel}>
          {tabs.map((t) => {
            const Icon = t.icon;
            const on = activeTab === t.key;
            return (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={on}
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

        {children({ activeTab, shared })}
      </div>
    </div>
  );
}

export default AdminTabPage;
