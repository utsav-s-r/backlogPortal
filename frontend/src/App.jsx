import { lazy, Suspense } from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { ThemeProvider } from "./context/ThemeContext";
import ProtectedStudentRoute from "./components/ProtectedStudentRoute";
import ProtectedAdminRoute from "./components/ProtectedAdminRoute";
import ErrorBoundary from "./components/ErrorBoundary";

// Route-level code splitting: each page loads as its own chunk on first visit, so a student never
// downloads the admin pages or vice versa. The route guards and theme provider stay in the entry
// chunk — tiny, and they gate rendering.
const HomePage = lazy(() => import("./pages/HomePage"));
const RegistrationPage = lazy(() => import("./pages/RegistrationPage"));
const AdminPage = lazy(() => import("./pages/AdminPage"));
const AdminLoginPage = lazy(() => import("./pages/AdminLoginPage"));
const ManageSubjectsPage = lazy(
  () => import("./pages/manageSubjects/ManageSubjectsPage"),
);
const ExamCyclePage = lazy(() => import("./pages/ExamCyclePage"));
const DepartmentsPage = lazy(() => import("./pages/DepartmentsPage"));
const ManageUsersPage = lazy(() => import("./pages/ManageUsersPage"));
const StudentsPage = lazy(() => import("./pages/students/StudentsPage"));
const ChangePasswordPage = lazy(() => import("./pages/ChangePasswordPage"));
const StudentLoginPage = lazy(() => import("./pages/StudentLoginPage"));
const StudentDashboardPage = lazy(() => import("./pages/StudentDashboardPage"));
const NotFoundPage = lazy(() => import("./pages/NotFoundPage"));

function App() {
  return (
    <ThemeProvider>
      <BrowserRouter>
        <ErrorBoundary>
          {/* blank full-height fallback: page chunks load near-instantly, so no
            spinner flash; min-h-screen keeps the layout from collapsing mid-swap */}
          <Suspense fallback={<div className="min-h-screen" />}>
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/student/login" element={<StudentLoginPage />} />
              <Route
                path="/student"
                element={
                  <ProtectedStudentRoute>
                    <StudentDashboardPage />
                  </ProtectedStudentRoute>
                }
              />
              <Route
                path="/register"
                element={
                  <ProtectedStudentRoute>
                    <RegistrationPage />
                  </ProtectedStudentRoute>
                }
              />
              <Route path="/admin/login" element={<AdminLoginPage />} />
              <Route
                path="/admin"
                element={
                  <ProtectedAdminRoute>
                    <AdminPage />
                  </ProtectedAdminRoute>
                }
              />
              <Route
                path="/admin/manage-subjects"
                element={
                  <ProtectedAdminRoute>
                    <ManageSubjectsPage />
                  </ProtectedAdminRoute>
                }
              />
              <Route
                path="/admin/exam-cycles"
                element={
                  <ProtectedAdminRoute>
                    <ExamCyclePage />
                  </ProtectedAdminRoute>
                }
              />
              <Route
                path="/admin/departments"
                element={
                  <ProtectedAdminRoute>
                    <DepartmentsPage />
                  </ProtectedAdminRoute>
                }
              />
              <Route
                path="/admin/users"
                element={
                  <ProtectedAdminRoute>
                    <ManageUsersPage />
                  </ProtectedAdminRoute>
                }
              />
              <Route
                path="/admin/students"
                element={
                  <ProtectedAdminRoute>
                    <StudentsPage />
                  </ProtectedAdminRoute>
                }
              />
              <Route
                path="/admin/change-password"
                element={
                  <ProtectedAdminRoute>
                    <ChangePasswordPage />
                  </ProtectedAdminRoute>
                }
              />
              {/* Catch-all, must stay last. Unmatched URLs previously rendered an empty body —
              no message, no navigation. Removed routes (the old /admin/add-subject and
              /admin/clone-subjects) deliberately land here rather than redirecting. */}
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </Suspense>
        </ErrorBoundary>
      </BrowserRouter>
    </ThemeProvider>
  );
}

export default App;
