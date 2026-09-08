import AdminLayout from "./AdminLayout";

// The container every non-tabbed admin section shares, inside AdminLayout's rail + band.
//
// No back-link, no `badge`, no `actions` slot, deliberately: the sidebar carries every destination,
// its identity block carries the department once, and Manage Users' "My Password" is a sidebar row.
// Any of them here is a seventh copy of what the rail already shows.
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
