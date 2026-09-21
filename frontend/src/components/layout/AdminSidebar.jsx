import {
  ArrowLeft,
  BookOpen,
  Building2,
  CalendarRange,
  FileText,
  IdCard,
  KeyRound,
  LogOut,
  PanelLeft,
  Users,
  X,
} from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import ThemeToggle from "../ui/ThemeToggle";
import { logoutAdmin } from "../../lib/api";
import {
  ADMIN_ONLY,
  ROLE,
  STAFF_ROLES,
  SUBJECT_ROLES,
  UNRESTRICTED,
  USER_MANAGEMENT_ROLES,
} from "../../lib/roles";

// The admin navigation, on all seven admin pages, and the only navigation the admin side has.
//
// Why all seven and not just /admin: navigation that appears and disappears as you move is two
// navigation models in one product. It is all-or-nothing. Role gating therefore lives in ONE place
// rather than once per page, and the highlighted row is what answers "which page am I on".
//
// UX GATING ONLY. These role lists decide what to offer, never what is permitted: every
// /api/admin/** call is authorized server-side per endpoint. Never let this be the reason an
// endpoint skips its @PreAuthorize.

// Sidebar row. The rail is a page surface, not a chrome colour, so hover is a real fill
// (`bg-surface-1` against the rail's `bg-surface-muted`) — never a white alpha and never a border.
const NAV_ITEM =
  "group relative flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold " +
  "text-ink transition-colors hover:bg-surface-1";

// Collapsed, the label becomes the tooltip: same words, beside the icon, NO DELAY. A `title`
// attribute waits about a second, which is why this is markup and not an attribute.
const TOOLTIP =
  "pointer-events-none absolute left-full top-1/2 z-50 ml-2 hidden -translate-y-1/2 " +
  "whitespace-nowrap rounded-lg bg-ink px-2.5 py-1.5 text-xs font-semibold text-surface-1 " +
  "group-hover:block";

const ROLE_LABELS = {
  ADMIN: "Administrator",
  PRINCIPAL: "Principal",
  HOD: "Head of Department",
  DEPT_OFFICE: "Department Office",
  PROCTOR: "Proctor",
};

// The six destinations. Per role that is 6 / 5 / 4 / 3 / 2
// items for ADMIN / PRINCIPAL / HOD / DEPT_OFFICE / PROCTOR — a proctor's two-item sidebar is thin
// and was accepted knowingly.
function destinationsFor(role) {
  const items = [{ icon: FileText, label: "Registrations", to: "/admin" }];
  if (STAFF_ROLES.includes(role))
    items.push({
      icon: IdCard,
      label: role === ROLE.PROCTOR ? "My Students" : "Students",
      to: "/admin/students",
    });
  if (SUBJECT_ROLES.includes(role))
    items.push({ icon: BookOpen, label: "Subjects", to: "/admin/manage-subjects" });
  if (ADMIN_ONLY.includes(role))
    items.push({ icon: CalendarRange, label: "Exam cycles", to: "/admin/exam-cycles" });
  if (UNRESTRICTED.includes(role))
    items.push({ icon: Building2, label: "Departments", to: "/admin/departments" });
  if (USER_MANAGEMENT_ROLES.includes(role))
    items.push({ icon: Users, label: "Users", to: "/admin/users" });
  return items;
}

function AdminSidebar({ collapsed, openMobile, onToggle, onClose }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const adminRole = sessionStorage.getItem("adminRole") || "";
  const adminUsername = sessionStorage.getItem("adminUsername") || "";
  const adminDepartment = sessionStorage.getItem("adminDepartment") || "";

  // The mobile drawer is always expanded: it covers the whole screen, so there is no rail to be in.
  const rail = collapsed && !openMobile;

  const label = (text) =>
    rail ? <span className={TOOLTIP}>{text}</span> : <span>{text}</span>;

  // Never `${A}${B}` for classes: NAV_ITEM ends in a class name, so adjacent interpolation fuses
  // both into one dead token ("hover:bg-surface-1justify-center") that Tailwind never emits —
  // silently losing both, with lint and the build still green.
  const cx = (...parts) => parts.filter(Boolean).join(" ");

  // No `px-0` here: NAV_ITEM carries `px-3`, and two padding utilities resolve by CSS SOURCE ORDER
  // (px-0 is emitted first), so it would lose anyway — and symmetric padding already centres the
  // icon in the rail.
  const rowClass = cx(NAV_ITEM, rail && "justify-center");

  // `Icon` is pulled out as a VARIABLE, not destructured in the parameter list: eslint's
  // varsIgnorePattern '^[A-Z_]' — which is what teaches this repo that `<Icon />` uses `Icon`,
  // since eslint-plugin-react is not installed — does not cover function parameters.
  const destination = (item) => {
    const Icon = item.icon;
    const { label: text, to } = item;
    const current = pathname === to;
    return (
      <Link
        key={to}
        to={to}
        onClick={onClose}
        aria-current={current ? "page" : undefined}
        className={cx(rowClass, current && "bg-accent-tint")}
      >
        <Icon size={18} />
        {label(text)}
      </Link>
    );
  };

  return (
    <aside
      className={`fixed inset-y-0 left-0 z-40 flex-col border-r border-stroke bg-surface-muted text-ink ${
        openMobile ? "flex w-full" : "hidden md:flex"
      } ${rail ? "md:w-[68px]" : "md:w-[260px]"}`}
    >
      {/* The toggle CARRIES the word "Menu" rather than sitting beside it: a bare label next to a
          button, on a bar where every other row is clickable, reads as clickable and is not. */}
      <div className="flex items-center px-3 py-4">
        <button
          type="button"
          onClick={onToggle}
          aria-label={`${rail ? "Expand" : "Collapse"} navigation`}
          data-cy="nav-toggle"
          className={`${rowClass} hidden md:flex`}
        >
          <PanelLeft size={18} />
          {label("Menu")}
        </button>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close navigation"
          data-cy="nav-close"
          className="inline-flex size-10 items-center justify-center rounded-lg text-ink transition-colors hover:bg-surface-1 md:hidden"
        >
          <X size={18} />
        </button>
      </div>

      <nav className="flex flex-1 flex-col gap-1 overflow-y-auto px-3">
        {destinationsFor(adminRole).map(destination)}

        {/* a hairline, not a box edge — the one separator the no-box rule allows */}
        <div className="my-3 h-px bg-stroke" />

        {/* ungated: accounts start on the derived default password, so every admin role needs a way
            here — Manage Users only reaches ADMIN/PRINCIPAL/HOD */}
        {destination({ icon: KeyRound, label: "My password", to: "/admin/change-password" })}
        {destination({ icon: ArrowLeft, label: "Home", to: "/" })}
        <button
          type="button"
          onClick={async () => {
            await logoutAdmin(); // expire the httpOnly cookie, then clear local state
            navigate("/admin/login");
          }}
          className={rowClass}
        >
          <LogOut size={18} />
          {label("Log out")}
        </button>
      </nav>

      <div className="px-3 pb-4">
        {/* Collapsed, the words drop but THE CONTROL STAYS — the switch itself is the icon, so
            nothing in the rail is decoration, and the label returns as the hover tooltip. */}
        <ThemeToggle
          className={cx(NAV_ITEM, rail ? "justify-center" : "justify-between")}
          label={label("Dark Mode")}
        />

        {/* Pinned to the bottom, and absent entirely from the rail — it does not shrink to an
            avatar. Department lives here now, where BrandHeader's badge used to be. */}
        {rail ? null : (
          <div className="px-3 pt-3 text-xs text-ink-muted">
            Signed in as
            <p className="text-sm font-semibold text-ink" data-cy="identity-username">
              {adminUsername}
            </p>
            <p>
              {ROLE_LABELS[adminRole] || adminRole}
              {adminDepartment ? ` · ${adminDepartment}` : ""}
            </p>
          </div>
        )}
      </div>
    </aside>
  );
}

export default AdminSidebar;
