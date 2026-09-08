import { BookOpen, Copy, PlusCircle, UploadCloud } from "lucide-react";
import AdminTabPage from "../../components/layout/AdminTabPage";
import ManageTab from "./ManageTab";
import AddSubjectTab from "./AddSubjectTab";
import CloneSubjectsTab from "./CloneSubjectsTab";
import ImportSubjectsTab from "./ImportSubjectsTab";
// DEPT_PINNED_NO_PROCTOR, not DEPT_PINNED: SubjectController excludes PROCTOR from the catalog
// entirely, so there is no proctor to pin here. See lib/roles.js on why the two sets stay separate.
import { DEPT_PINNED_NO_PROCTOR, SUBJECT_ROLES } from "../../lib/roles";

// Tab identity -> presentational component. The active tab comes from the ?tab= query param, so
// it survives refresh and is shareable; AdminTabPage whitelists the value to these keys.
const TABS = [
  { key: "manage", label: "Manage", icon: BookOpen },
  { key: "add", label: "Add", icon: PlusCircle },
  { key: "clone", label: "Clone", icon: Copy },
  { key: "import", label: "Import", icon: UploadCloud },
];

// The subject catalog behind one route as four tabs (Manage / Add / Clone / Import), mirroring the
// Students page. Clone rolls a year forward; Import loads a catalog that has no previous year to
// roll. AdminTabPage owns everything the tabs share — role guard, the one departments fetch,
// dept-pin resolution, chrome — leaving each tab purely presentational. Auth/dept scope is enforced
// server-side on every /api/admin/** call; the role/dept logic here is only UX gating.
function ManageSubjectsPage() {
  return (
    <AdminTabPage
      allowedRoles={SUBJECT_ROLES}
      deptPinnedRoles={DEPT_PINNED_NO_PROCTOR}
      tabs={TABS}
      ariaLabel="Subject catalog"
    >
      {({ activeTab, shared }) => (
        <>
          {activeTab === "manage" && <ManageTab {...shared} />}
          {activeTab === "add" && <AddSubjectTab {...shared} />}
          {activeTab === "clone" && <CloneSubjectsTab {...shared} />}
          {activeTab === "import" && <ImportSubjectsTab {...shared} />}
        </>
      )}
    </AdminTabPage>
  );
}

export default ManageSubjectsPage;
