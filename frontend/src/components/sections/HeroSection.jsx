import { useEffect, useState } from "react";
import { ArrowRight, ShieldCheck } from "lucide-react";
import { Link } from "react-router-dom";
import api from "../../lib/api";
import PrimaryCta from "../ui/PrimaryCta";
import { btn } from "../../lib/buttonClasses";

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
    // page. No position/z-index — there is no backdrop layer here to stack against.
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
          {/* open/closed is data, not theme: each state is one FILL, and the token behind it
              carries its own dark re-tint. No border — the fill is the badge. */}
          {regStatus !== null && (
            <span
              className={`inline-flex self-center items-center gap-2 rounded-full px-4 py-1.5 text-xs font-semibold uppercase tracking-[0.12em] ${
                regStatus.open
                  ? "bg-success-tint text-success"
                  : "bg-surface-muted text-secondary-ink"
              }`}
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
            <PrimaryCta as={Link} to="/register" className="gap-2">
              Start Registration <ArrowRight size={16} />
            </PrimaryCta>
            {/* Was a 2px outline. The button rule makes it a FILL, and the colour it already
                carried was the brand navy — so navy fill, maroon on hover. */}
            <Link
              to="/admin/login"
              className={btn("accent")}
            >
              <ShieldCheck size={16} /> Admin Access
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
