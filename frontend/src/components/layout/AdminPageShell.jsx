import AdminLayout from "./AdminLayout";

// The container every non-tabbed admin section shares, inside AdminLayout's rail + navy band.
//
// The navy BrandHeader and its lone "Dashboard" pill are gone: the sidebar carries every
// destination now, so a per-page back-link to /admin is one of seven copies of what the rail
// already shows. `badge` went with it — the department it displayed lives in the sidebar's identity
// block, once, instead of on each page. The one extra pill any page carried — Manage Users' "My
// Password" — is a sidebar row now, so the `actions` slot went too.
//
// AdminPage is deliberately NOT a caller: skip link first, max-w-7xl, and filter/table specs that
// assert raw ids and parent-child structure. It composes AdminLayout directly.
function AdminPageShell({
  // Passed, not derived: the 5xl pages carry `pb-8` and the 3xl ones don't. Incidental, but
  // normalising it would be an unrequested 2rem spacing change.
  containerClassName,
  children,
}) {
  return (
    <AdminLayout>
      <div className="px-4 py-8 sm:px-6 lg:px-8">
        <div className={`mx-auto w-full ${containerClassName}`}>
          {children}
        </div>
      </div>
    </AdminLayout>
  );
}

export default AdminPageShell;
