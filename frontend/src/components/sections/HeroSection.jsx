import { useEffect, useState } from "react";
import { ArrowRight, ShieldCheck } from "lucide-react";
import { Link } from "react-router-dom";
import api from "../../lib/api";
import MagneticCta from "../ui/MagneticCta";

export default function HeroSection() {
  // null = still checking; otherwise { open, cycleName?, examMonthYear? }
  const [regStatus, setRegStatus] = useState(null);

  useEffect(() => {
    api
      .get("/registration-status")
      // fail closed like the registration page: never claim a cycle is open unconfirmed
      .then((res) => setRegStatus(res.data))
      .catch(() => setRegStatus({ open: false }));
  }, []);

  return (
    // Transparent: the page background comes from HomePage's bg-surface-1, same as every other
    // page. The section carried position/z-index only to layer over a fixed backdrop that no
    // longer exists.
    <section
      style={{
        padding: "3rem 1rem 5rem",
      }}
    >
      {/* Content */}
      <div
        style={{
          margin: "0 auto",
          maxWidth: "80rem",
        }}
      >
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            gap: "1.5rem",
            textAlign: "center",
          }}
        >
          {regStatus !== null && (
            <span
              style={{
                display: "inline-flex",
                alignSelf: "center",
                borderRadius: "9999px",
                padding: "6px 16px",
                fontSize: "11px",
                fontWeight: 600,
                letterSpacing: "0.14em",
                textTransform: "uppercase",
                // open/closed is data, not theme — the theme half lives in the tokens
                background: regStatus.open
                  ? "var(--hero-badge-open-bg)"
                  : "var(--hero-badge-closed-bg)",
                border: regStatus.open
                  ? "1px solid var(--hero-badge-open-border)"
                  : "1px solid var(--hero-badge-closed-border)",
                color: regStatus.open
                  ? "var(--hero-badge-open-text)"
                  : "var(--hero-badge-closed-text)",
              }}
            >
              {regStatus.open
                ? `${regStatus.cycleName ? `${regStatus.cycleName} — ` : ""}Registrations Open`
                : "Registrations Currently Closed"}
            </span>
          )}

          <h1
            style={{
              fontFamily: '"Playfair Display", Georgia, serif',
              fontSize: "clamp(2.5rem, 5vw, 3.75rem)",
              lineHeight: 1.15,
              margin: 0,
              color: "var(--hero-text)",
            }}
          >
            Register for your backlog exam
          </h1>

          <p
            style={{
              maxWidth: "36rem",
              fontSize: "1.05rem",
              lineHeight: 1.65,
              margin: "0 auto",
              color: "var(--hero-text)",
            }}
          >
            Follow the simple steps below to submit your backlog registration.
            Download your form, get it signed, and submit for verification.
          </p>

          <div
            style={{
              display: "flex",
              flexWrap: "wrap",
              gap: "0.75rem",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <MagneticCta as={Link} to="/register" className="gap-2">
              Start Registration <ArrowRight size={16} />
            </MagneticCta>
            <Link
              to="/admin/login"
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: "8px",
                borderRadius: "9999px",
                border: "2px solid var(--hero-btn-border)",
                padding: "10px 20px",
                fontSize: "14px",
                fontWeight: 600,
                color: "var(--hero-btn-text)",
                background: "var(--hero-btn-bg)",
                transition: "all 0.2s",
                textDecoration: "none",
              }}
            >
              <ShieldCheck size={16} /> Admin Access
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
