import { useState, useEffect } from "react";
import {
  ArrowLeft,
  Lock,
  LoaderCircle,
  GraduationCap,
  Briefcase,
  Building2,
  ShieldCheck,
  UserCheck,
} from "lucide-react";
import AlertBanner from "../components/AlertBanner";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import BrandHeader from "../components/layout/BrandHeader";
import MagneticCta from "../components/ui/MagneticCta";
import api, { getAdminToken, logoutAdmin } from "../lib/api";
import { rememberExpiry } from "../lib/session";

const DEPT_ROLES = new Set(["HOD", "DEPT_OFFICE", "PROCTOR"]);

// The five designation cards. Titles are load-bearing: several Cypress specs select a card by its
// exact text, and `role` is what the login request sends.
const ROLES = [
  { role: "ADMIN", title: "Administrator", blurb: "Full system access", Icon: ShieldCheck },
  {
    role: "PRINCIPAL",
    title: "Principal / Registrar / COE",
    blurb: "High-level overview and final approvals",
    Icon: GraduationCap,
  },
  {
    role: "HOD",
    title: "Head of Department (HOD)",
    blurb: "Department level verification and tracking",
    Icon: Briefcase,
  },
  {
    role: "DEPT_OFFICE",
    title: "Department Office",
    blurb: "Manage physical form submissions",
    Icon: Building2,
  },
  {
    role: "PROCTOR",
    title: "Proctor",
    blurb: "Supervise and manage your assigned students",
    Icon: UserCheck,
  },
];

function AdminLoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionExpired = searchParams.get("expired") === "1";
  const [step, setStep] = useState(1);
  const [roleTitle, setRoleTitle] = useState("");
  const [selectedRole, setSelectedRole] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const [departments, setDepartments] = useState([]);
  // Separate from `error`: a failed departments fetch is not a login attempt failing, and it must
  // survive the setError("") that starts every submit.
  const [departmentsError, setDepartmentsError] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  // Already signed in (e.g. back via "Admin Access") — skip the form and return to the dashboard.
  // The marker is only a presence hint: if the cookie really expired, the dashboard's 401
  // interceptor bounces back with ?expired=1, which clears the marker and suppresses this.
  useEffect(() => {
    if (!sessionExpired && getAdminToken()) {
      navigate("/admin", { replace: true });
    }
  }, [sessionExpired, navigate]);

  useEffect(() => {
    api
      .get("/departments")
      .then((res) => {
        setDepartments(res.data);
        setDepartmentsError("");
      })
      // Swallowing this made HOD/DEPT_OFFICE/PROCTOR unable to sign in at all: with no options the
      // department <select> stays empty, so the "Please select your department." guard below can
      // never be satisfied and blames the user for a server-side failure.
      .catch(() => {
        setDepartments([]);
        setDepartmentsError(
          "Could not load departments. Refresh the page, or contact the administrator if this continues.",
        );
      });
  }, []);

  const handleRoleSelect = (title, role) => {
    setRoleTitle(title);
    setSelectedRole(role);
    setStep(2);
    setError("");
  };

  const handleLogin = async () => {
    if (!username || !password) {
      setError("Username and password are required.");
      return;
    }
    if (DEPT_ROLES.has(selectedRole) && !departmentId) {
      setError("Please select your department.");
      return;
    }

    setLoading(true);
    setError("");

    try {
      const payload = { username, password };
      if (DEPT_ROLES.has(selectedRole) && departmentId) {
        payload.departmentId = departmentId;
      }

      const res = await api.post("/auth/login", payload);

      const validRoles = ["ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE", "PROCTOR"];
      if (validRoles.includes(res.data.role)) {
        // the server set the JWT in an httpOnly cookie; store only a presence marker, UI state,
        // and the sign-out deadline (expiresIn)
        sessionStorage.setItem("adminRole", res.data.role);
        sessionStorage.setItem("adminToken", "cookie");
        sessionStorage.setItem("adminUsername", username);
        rememberExpiry("admin", res.data.expiresIn);
        if (res.data.departmentName) {
          sessionStorage.setItem("adminDepartment", res.data.departmentName);
        } else {
          sessionStorage.removeItem("adminDepartment");
        }
        // The id is the stable identity; the name is display only and is editable, so pinning on
        // it silently breaks for every signed-in dept user the moment a department is renamed.
        if (res.data.departmentId) {
          sessionStorage.setItem("adminDepartmentId", String(res.data.departmentId));
        } else {
          sessionStorage.removeItem("adminDepartmentId");
        }

        const redirectUrl = searchParams.get("redirect");
        if (redirectUrl) {
          navigate(redirectUrl);
        } else {
          navigate("/admin");
        }
      } else {
        // login succeeded server-side (cookie set) but the role is unexpected — clear the
        // cookie too, not just local state
        logoutAdmin();
        setError("Unauthorized role.");
      }
    } catch (apiError) {
      setError(apiError.response?.data?.message || "Login failed.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface-1 px-4 py-10 sm:px-6 lg:px-8">
      <a
        href="#admin-login-main"
        className="sr-only left-4 top-4 z-[60] rounded-md bg-cta px-4 py-2 text-sm font-semibold text-cta-text focus:not-sr-only focus:fixed"
      >
        Skip to admin login
      </a>

      <div
        id="admin-login-main"
        className="mx-auto w-full max-w-md rounded-3xl border border-stroke bg-surface-1 p-6 shadow-soft sm:p-8"
      >
        <div className="mb-6 text-left">
          <BrandHeader className="mb-4" />
          <p className="mb-2 inline-flex rounded-full border border-primary/30 bg-surface-muted px-3 py-1 text-xs font-semibold uppercase tracking-[0.12em] text-primary-ink">
            Restricted Access
          </p>
          <h1 className="admin-login-heading text-3xl font-semibold text-secondary-ink">
            {step === 1 ? "Select Designation" : "Staff Login"}
          </h1>
          <p className="mt-2 text-sm text-ink">
            {step === 1
              ? "Please select your designation to continue."
              : `Sign in as ${roleTitle} to manage registrations.`}
          </p>
        </div>

        {sessionExpired ? (
          <AlertBanner
            tone="warning"
            role="status"
            data-cy="session-expired"
            className="mb-4"
          >
            Your session expired. Please sign in again.
          </AlertBanner>
        ) : null}

        {step === 1 ? (
          <div className="flex flex-col gap-4">
            {/* `card.Icon`, not a destructured `Icon`: eslint-plugin-react is not enabled, so JSX
                use of a destructured PARAM isn't seen as a use and no-unused-vars fires (the
                config's varsIgnorePattern covers vars, not args). */}
            {ROLES.map((card) => (
              <button
                key={card.role}
                onClick={() => handleRoleSelect(card.title, card.role)}
                data-cy={`role-${card.role.toLowerCase().replace("_", "-")}`}
                className="flex items-center gap-4 rounded-2xl border border-stroke bg-surface-muted p-4 text-left transition-all duration-200 hover:border-primary hover:bg-primary-tint focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
              >
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-surface-1 text-primary-ink shadow-sm">
                  <card.Icon size={24} />
                </div>
                <div>
                  <h3 className="admin-login-heading font-semibold text-secondary-ink">{card.title}</h3>
                  <p className="mt-0.5 text-xs text-ink">{card.blurb}</p>
                </div>
              </button>
            ))}
          </div>
        ) : (
          <div className="space-y-4">
            <label
              htmlFor="admin-username"
              className="block text-left text-xs font-semibold uppercase tracking-[0.08em] text-ink"
            >
              Username
            </label>
            <input
              id="admin-username"
              placeholder="Username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring"
              data-cy="admin-username"
            />

            <label
              htmlFor="admin-password"
              className="block text-left text-xs font-semibold uppercase tracking-[0.08em] text-ink"
            >
              Password
            </label>
            <input
              id="admin-password"
              type="password"
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 placeholder:text-ink-muted focus-visible:ring-2 focus-visible:ring-focus-ring"
              data-cy="admin-password"
            />

            {DEPT_ROLES.has(selectedRole) && (
              <>
                <label
                  htmlFor="admin-department"
                  className="block text-left text-xs font-semibold uppercase tracking-[0.08em] text-ink"
                >
                  Department
                </label>
                <select
                  id="admin-department"
                  value={departmentId}
                  onChange={(e) => setDepartmentId(e.target.value)}
                  className="w-full rounded-xl border border-stroke bg-surface-1 px-3.5 py-2.5 text-sm text-ink outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-focus-ring"
                  data-cy="admin-department"
                >
                  <option value="">Select department</option>
                  {departments.map((d) => (
                    <option key={d.id} value={String(d.id)}>
                      {d.deptName}
                    </option>
                  ))}
                </select>
                {departmentsError ? (
                  <AlertBanner
                    tone="error"
                    role="alert"
                    data-cy="admin-departments-error"
                  >
                    {departmentsError}
                  </AlertBanner>
                ) : null}
              </>
            )}

            {error ? (
              <AlertBanner tone="error" role="alert" aria-live="polite">
                {error}
              </AlertBanner>
            ) : null}

            <MagneticCta
              onClick={handleLogin}
              className="mt-2 w-full gap-2 rounded-xl"
              disabled={loading}
              data-cy="admin-login-submit"
              aria-label="Admin login"
            >
              {loading ? (
                <LoaderCircle size={16} className="animate-spin" />
              ) : (
                <Lock size={16} />
              )}{" "}
              Login
            </MagneticCta>

            <button
              type="button"
              onClick={() => {
                setStep(1);
                setUsername("");
                setPassword("");
                setDepartmentId("");
                setSelectedRole("");
                setError("");
              }}
              className="mt-4 flex w-full items-center justify-center gap-2 text-sm font-medium text-ink hover:text-primary-ink"
            >
              <ArrowLeft size={14} /> Back to role selection
            </button>
          </div>
        )}

        <div className="mt-4 text-center">
          <Link
            to="/"
            className="login-back-link inline-flex items-center gap-1 text-sm font-medium text-secondary-ink underline-offset-4 hover:underline"
          >
            <ArrowLeft size={14} /> Back to home
          </Link>
        </div>
      </div>
    </div>
  );
}

export default AdminLoginPage;
