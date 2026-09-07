import { useState } from "react";
import { Menu } from "lucide-react";
import AdminSidebar from "./AdminSidebar";
import TopBand from "./TopBand";

// The chrome every admin page shares: the fixed left rail, the full-bleed navy band across the top
// of the content, and the push. It replaces BrandHeader on the admin side entirely.
//
// PUSH, not overlay (decision 4): the content's left inset tracks the rail's width on desktop, and
// is zero on mobile, where the drawer covers the WHOLE screen instead — full width and full height,
// no partial panel and no scrim (decision 5).
//
// The rail's open/collapsed state lives here rather than in a context or storage: it is one boolean
// that only this subtree reads, and it resets on navigation, which nobody has asked to change.
function AdminLayout({ children }) {
  const [collapsed, setCollapsed] = useState(false);
  const [openMobile, setOpenMobile] = useState(false);

  return (
    <div className="min-h-screen bg-surface-1 text-ink">
      <AdminSidebar
        collapsed={collapsed}
        openMobile={openMobile}
        onToggle={() => setCollapsed((c) => !c)}
        onClose={() => setOpenMobile(false)}
      />

      <div className={`min-h-screen ${collapsed ? "md:pl-[68px]" : "md:pl-[260px]"}`}>
        <TopBand>
          <button
            type="button"
            onClick={() => setOpenMobile(true)}
            aria-label="Open navigation"
            data-cy="nav-open"
            className="inline-flex size-10 items-center justify-center rounded-lg text-ink transition-colors hover:bg-surface-muted md:hidden"
          >
            <Menu size={18} />
          </button>
        </TopBand>

        {children}
      </div>
    </div>
  );
}

export default AdminLayout;
