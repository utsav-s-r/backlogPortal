import { GraduationCap, TrendingUp, UploadCloud, UserCheck, UserPlus, Users } from "lucide-react";
import AdminTabPage from "../../components/layout/AdminTabPage";
import StudentsManageTab from "./StudentsManageTab";
import AddStudentTab from "./AddStudentTab";
import ImportStudentsTab from "./ImportStudentsTab";
import ClaimStudentsTab from "./ClaimStudentsTab";
import BulkProgressionTab from "./BulkProgressionTab";
import { DEPT_PINNED, ROLE, STAFF_ROLES } from "../../lib/roles";

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
// (owner decision); the server enforces it with hasRole('ADMIN').
const BULK_TAB = { key: "bulk", label: "Bulk Progression", icon: TrendingUp };
const PROCTOR_TABS = [
  { key: "manage", label: "My Students", icon: Users },
  { key: "claim", label: "Claim Students", icon: UserCheck },
];

function tabsForRole(role) {
  if (role === ROLE.PROCTOR) return PROCTOR_TABS;
  if (role === ROLE.ADMIN) return [...STAFF_TABS, PROCTORS_TAB, BULK_TAB];
  if ([ROLE.PRINCIPAL, ROLE.HOD].includes(role)) return [...STAFF_TABS, PROCTORS_TAB];
  return STAFF_TABS;
}

// The page title and blurb, both role-dependent. Passed as AdminTabPage's `heading` rather than
// living in it: the subject catalog deliberately has no heading, so this belongs to the page.
function Heading({ adminRole, adminDepartment, deptLocked }) {
  const proctor = adminRole === ROLE.PROCTOR;
  return (
    <div className="mb-3">
      <h1 className="inline-flex items-center gap-2 text-2xl font-semibold text-secondary-ink sm:text-3xl">
        <GraduationCap size={26} /> {proctor ? "My Students" : "Students"}
      </h1>
      <p className="mt-1 text-sm text-ink-muted">
        {proctor
          ? "Manage the students under your supervision — details, semester timeline, and DOB resets — and claim new ones from your department."
          : "Create and manage student accounts. Each semester's academic year is seeded on create; correct one from a student's Semesters panel on the Manage tab."}
        {deptLocked && adminDepartment ? ` Scoped to ${adminDepartment}.` : ""}
      </p>
    </div>
  );
}

// Admin student management behind one route as tabs. AdminTabPage owns everything the tabs share —
// role guard, the single departments fetch, dept-pin resolution, chrome — leaving each tab
// presentational. Auth/dept scope is enforced server-side on /api/admin/students/** and
// /api/admin/progression/**.
function StudentsPage() {
  return (
    <AdminTabPage
      allowedRoles={STAFF_ROLES}
      deptPinnedRoles={DEPT_PINNED}
      tabs={tabsForRole(sessionStorage.getItem("adminRole") || "")}
      ariaLabel="Student management"
      heading={({ shared }) => <Heading {...shared} />}
    >
      {({ activeTab, shared }) => (
        <>
          {activeTab === "manage" && <StudentsManageTab {...shared} />}
          {activeTab === "add" && <AddStudentTab {...shared} />}
          {activeTab === "import" && <ImportStudentsTab {...shared} />}
          {(activeTab === "claim" || activeTab === "proctors") && <ClaimStudentsTab {...shared} />}
          {activeTab === "bulk" && <BulkProgressionTab {...shared} />}
        </>
      )}
    </AdminTabPage>
  );
}

export default StudentsPage;
