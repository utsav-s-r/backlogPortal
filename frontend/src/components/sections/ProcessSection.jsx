import { CheckCircle2, FileSignature, ListChecks, LogIn } from "lucide-react";

const steps = [
  {
    icon: LogIn,
    title: "Log In",
    text: "Sign in with your USN and date of birth.",
  },
  {
    icon: ListChecks,
    title: "Select Subjects",
    text: "Pick the backlog subjects you are eligible to register for.",
  },
  {
    icon: FileSignature,
    title: "Download & Sign",
    text: "Download the pre-filled PDF form and get it signed by your Proctor and HOD.",
  },
  {
    icon: CheckCircle2,
    title: "Submit for Verification",
    text: "Hand the signed form to your department office — it is verified and tracked centrally.",
  },
];

export default function ProcessSection() {
  return (
    <section id="how-it-works" className="px-4 py-16 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-3xl">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary-ink">
            How It Works
          </p>
          <h2 className="mt-2 text-3xl text-primary-ink sm:text-4xl">
            From Login to Verification
          </h2>
          <p className="mt-3 max-w-xl text-ink-muted">
            Four steps take your backlog registration from start to verified.
          </p>
        </div>

        <ol className="mt-10">
          {steps.map((step, index) => {
            const Icon = step.icon;
            const last = index === steps.length - 1;

            return (
              <li key={step.title} className="relative flex gap-5 pb-10 last:pb-0">
                {/* connector line between the numbered dots */}
                {!last && (
                  <span
                    aria-hidden="true"
                    className="absolute left-[22px] top-12 h-[calc(100%-2.5rem)] w-px bg-stroke"
                  />
                )}
                <span className="relative z-10 inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-secondary text-white shadow-soft">
                  <Icon size={18} />
                </span>
                <div className="pt-1">
                  <h3 className="text-lg font-semibold text-ink">
                    <span className="mr-2 text-sm font-bold text-primary-ink">
                      {String(index + 1).padStart(2, "0")}
                    </span>
                    {step.title}
                  </h3>
                  <p className="mt-1 max-w-xl text-sm leading-relaxed text-ink-muted">{step.text}</p>
                </div>
              </li>
            );
          })}
        </ol>
      </div>
    </section>
  );
}
