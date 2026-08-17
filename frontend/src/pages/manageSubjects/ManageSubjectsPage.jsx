import { useState, useEffect, useMemo } from "react";
import { ArrowLeft, BookOpen, Copy, PlusCircle } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import BrandHeader from "../../components/layout/BrandHeader";
import api from "../../lib/api";
import { reportLoadError } from "../../lib/loadError";
import ManageTab from "./ManageTab";
import AddSubjectTab from "./AddSubjectTab";
import CloneSubjectsTab from "./CloneSubjectsTab";
import { findOwnDepartment } from "../../lib/session";
import AlertBanner from "../../components/AlertBanner";

const DEPT_ROLES = new Set(["HOD", "DEPT_OFFICE"]);
const ALLOWED_ROLES = ["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE"];

// Tab identity -> presentational component. The active tab comes from the ?tab= query param, so
// it survives refresh and is shareable; the value is whitelisted to these keys, else "manage".
const TABS = [
  { key: "manage", label: "Manage", icon: BookOpen },
  { key: "add", label: "Add", icon: PlusCircle },
  { key: "clone", label: "Clone", icon: Copy },
];
const TAB_KEYS = new Set(TABS.map((t) => t.key));

// The subject catalog behind one route as three tabs (Manage / Add / Clone). This shell owns what
// they share — role guard, the one departments fetch, dept-pin resolution — and passes them down,
// leaving each tab purely presentational. Auth/dept scope is enforced server-side on every
// /api/admin/** call; the role/dept logic here is only UX gating.
function ManageSubjectsPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const adminRole = sessionStorage.getItem("adminRole") || "";
  const adminDepartment = sessionStorage.getItem("adminDepartment") || "";
  const deptLocked = DEPT_ROLES.has(adminRole);

  const [departments, setDepartments] = useState([]);
  const [deptError, setDeptError] = useState("");

  // Dept-scoped roles are pinned to their own department across every tab: pure derivation from
  // (departments, role, dept), computed during render rather than stored via an effect, so it
  // resolves on first paint with no cascading render.
  const pinnedDeptId = useMemo(() => {
    if (!(deptLocked && adminDepartment && departments.length > 0)) return "";
    const mine = findOwnDepartment(departments, adminDepartment);
    return mine ? String(mine.id) : "";
  }, [departments, deptLocked, adminDepartment]);

  // whitelist the query param; default + fall back to "manage"
  const tabParam = searchParams.get("tab");
  const activeTab = TAB_KEYS.has(tabParam) ? tabParam : "manage";
  const setActiveTab = (key) => setSearchParams({ tab: key }, { replace: true });

  useEffect(() => {
    if (!ALLOWED_ROLES.includes(adminRole)) {
      navigate("/admin");
      return;
    }
    api
      .get("/departments")
      .then((res) => {
        setDepartments(res.data);
        setDeptError("");
      })
      .catch((err) =>
        reportLoadError(
          err,
          setDeptError,
          "Could not load departments. Some filters may be unavailable — refresh to retry.",
        ),
      );
  }, [adminRole, navigate]);


  const shared = { departments, adminRole, adminDepartment, deptLocked, pinnedDeptId };

  return (
    <div className="min-h-screen bg-surface-1 px-4 py-8 text-ink sm:px-6 lg:px-8">
      <div className="mx-auto w-full max-w-5xl pb-8">
        <BrandHeader className="mb-6">
          <Link
            to="/admin"
            className="inline-flex items-center rounded-full border border-white/30 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-white/10"
          >
            <ArrowLeft size={15} className="mr-1" /> Dashboard
          </Link>
        </BrandHeader>

        <div className="mb-6 flex flex-wrap gap-2" role="tablist" aria-label="Subject catalog">
          {TABS.map((t) => {
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
          <AlertBanner
            tone="error"
            role="alert"
            data-cy="dept-load-error"
            className="mb-3"
          >
            {deptError}
          </AlertBanner>
        ) : null}

        {activeTab === "manage" && <ManageTab {...shared} />}
        {activeTab === "add" && <AddSubjectTab {...shared} />}
        {activeTab === "clone" && <CloneSubjectsTab {...shared} />}
      </div>
    </div>
  );
}

export default ManageSubjectsPage;
