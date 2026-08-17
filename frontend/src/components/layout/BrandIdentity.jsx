import logo from "../../assets/MSRIT.png";

// `onSurface`: set when the brand sits on the page/card background (login, change-password) rather
// than a navy header bar — the wordmark then uses the theme text color to stay legible in light.
export default function BrandIdentity({ compact = false, wordmarkClassName = "", onSurface = false }) {
  return (
    <div className="flex items-center gap-3">
      <img
        src={logo}
        alt="Ramaiah Institute of Technology"
        className={compact ? "h-10 w-auto" : "h-12 w-auto"}
      />
      <div className={`leading-none ${wordmarkClassName}`}>
        <p
          className={`${compact ? "text-base" : "text-lg sm:text-xl"} font-extrabold tracking-[0.06em] ${
            onSurface
              ? "text-secondary-ink"
              : "text-white drop-shadow-[0_1px_6px_rgba(0,0,0,0.35)]"
          }`}
          style={{ fontFamily: '"Sora", "Inter", sans-serif' }}
        >
          Backlog Registration
        </p>
      </div>
    </div>
  );
}
