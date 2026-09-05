import logo from "../../assets/MSRIT.png";

// The logo PNG is a white-knockout asset — its baked-in "Ramaiah Institute of Technology" wordmark
// is white on transparent — so this only reads on a dark ground. Every caller is either BrandHeader's
// navy box or StickyNav over the hero; put it on a light surface and half the image vanishes.
export default function BrandIdentity({ compact = false, wordmarkClassName = "" }) {
  return (
    <div className="flex items-center gap-3">
      <img
        src={logo}
        alt="Ramaiah Institute of Technology"
        className={compact ? "h-10 w-auto" : "h-12 w-auto"}
      />
      <div className={`leading-none ${wordmarkClassName}`}>
        <p
          className={`${compact ? "text-base" : "text-lg sm:text-xl"} font-extrabold tracking-[0.06em] text-white drop-shadow-[0_1px_6px_rgba(0,0,0,0.35)]`}
          style={{ fontFamily: '"Sora", "Inter", sans-serif' }}
        >
          Backlog Registration
        </p>
      </div>
    </div>
  );
}
