import { useState, useEffect } from "react";
import { ArrowLeft, GraduationCap, TrendingUp, UploadCloud, UserCheck, UserPlus, Users } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import BrandHeader from "../../components/layout/BrandHeader";
import api from "../../lib/api";
import { reportLoadError } from "../../lib/loadError";
import StudentsManageTab from "./StudentsManageTab";
import AddStudentTab from "./AddStudentTab";
import ImportStudentsTab from "./ImportStudentsTab";
import ClaimStudentsTab from "./ClaimStudentsTab";
import BulkProgressionTab from "./BulkProgressionTab";
import { findOwnDepartment } from "../../lib/session";
import AlertBanner from "../../components/AlertBanner";

const DEPT_ROLES = new Set(["HOD", "DEPT_OFFICE", "PROCTOR"]);
const ALLOWED_ROLES = ["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE", "PROCTOR"];

// Tab set varies by role: a PROCTOR gets only their supervised roster and the claim picker (no
// create/import — the server refuses those anyway); HOD and above also manage proctor
// assignments; DEPT_OFFICE keeps the base three, without proctor management.
const STAFF_TABS = [
  { key: "manage", label: "Manage", icon: Users },
  { key: "add", label: "Add", icon: UserPlus },
  { key: "import", label: "Import", icon: UploadCloud },
];
const PROCTORS_TAB = { key: "proctors", label: "Proctors", icon: UserCheck };
// ADMIN only — an institution-wide write, not a dept one. PRINCIPAL is deliberately excluded too
// (owner decision 2026-08-17); the server enforces it with hasRole('ADMIN').
const BULK_TAB = { key: "bulk", label: "Bulk Progression", icon: TrendingUp };
const PROCTOR_TABS = [
  { key: "manage", label: "My Students", icon: Users },
  { key: "claim", label: "Claim Students", icon: UserCheck },
];

function tabsForRole(role) {
  if (role === "PROCTOR") return PROCTOR_TABS;
  if (role === "ADMIN") return [...STAFF_TABS, PROCTORS_TAB, BULK_TAB];
  if (["PRINCIPAL", "HOD"].includes(role)) return [...STAFF_TABS, PROCTORS_TAB];
  return STAFF_TABS;
}

// Admin student management behind one route as tabs (Manage / Add / Import). The shell owns what
// they share — role guard, the single departments fetch, dept-pin resolution — leaving each tab
// presentational. Auth/dept scope is enforced server-side on /api/admin/students/** and
// /api/admin/progression/**.
function StudentsPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const adminRole = sessionStorage.getItem("adminRole") || "";
  const adminDepartment = sessionStorage.getItem("adminDepartment") || "";
  const deptLocked = DEPT_ROLES.has(adminRole);

  const [departments, setDepartments] = useState([]);
  const [deptError, setDeptError] = useState("");

  const tabs = tabsForRole(adminRole);
  const tabKeys = new Set(tabs.map((t) => t.key));

  // Dept-scoped roles are pinned to their own department: pure derivation from (departments,
  // role, dept), computed during render rather than stored via an effect, so it resolves on first
  // paint with no cascading re-render. Left as a plain expression — the React Compiler couldn't
  // preserve a manual useMemo once the role-dependent tab derivation joined this render, and the
  // find() is cheap enough per render.
  const pinnedDept =
    deptLocked && adminDepartment && departments.length > 0
      ? findOwnDepartment(departments, adminDepartment)
      : null;
  const pinnedDeptId = pinnedDept ? String(pinnedDept.id) : "";

  const tabParam = searchParams.get("tab");
  const activeTab = tabKeys.has(tabParam) ? tabParam : "manage";
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
      // a swallowed failure here silently un-pins a dept-scoped user's department
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

        <div className="mb-3">
          <h1 className="inline-flex items-center gap-2 text-2xl font-semibold text-secondary-ink sm:text-3xl">
            <GraduationCap size={26} /> {adminRole === "PROCTOR" ? "My Students" : "Students"}
          </h1>
          <p className="mt-1 text-sm text-ink-muted">
            {adminRole === "PROCTOR"
              ? "Manage the students under your supervision — details, semester timeline, and DOB resets — and claim new ones from your department."
              : "Create and manage student accounts. Each semester's academic year is seeded on create; correct one from a student's Semesters panel on the Manage tab."}
            {deptLocked && adminDepartment ? ` Scoped to ${adminDepartment}.` : ""}
          </p>
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


        <div className="mb-6 flex flex-wrap gap-2" role="tablist" aria-label="Student management">
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

        {activeTab === "manage" && <StudentsManageTab {...shared} />}
        {activeTab === "add" && <AddStudentTab {...shared} />}
        {activeTab === "import" && <ImportStudentsTab {...shared} />}
        {(activeTab === "claim" || activeTab === "proctors") && <ClaimStudentsTab {...shared} />}
        {activeTab === "bulk" && <BulkProgressionTab {...shared} />}
      </div>
    </div>
  );
}

export default StudentsPage;
