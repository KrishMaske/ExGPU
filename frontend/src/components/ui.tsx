import type {
  ButtonHTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
} from "react";

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

/**
 * A content panel.
 *
 * Uses a hairline border plus a very soft shadow rather than a heavy outline — on a white
 * page, a hard border on every card turns the layout into a grid of boxes. The header is
 * separated by spacing, not a divider, unless the card holds a table.
 */
export function Card({
  title,
  subtitle,
  actions,
  children,
  className,
  flush,
}: {
  title?: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  /** Drop the body padding — for tables that should run edge to edge. */
  flush?: boolean;
}) {
  return (
    <section
      className={cx(
        "rounded-2xl border border-line bg-surface shadow-card",
        className
      )}
    >
      {(title || actions) && (
        <header className="flex items-start justify-between gap-4 px-4 pb-4 pt-5 sm:px-6">
          <div>
            {title && (
              <h2 className="text-base font-semibold tracking-tight text-ink">
                {title}
              </h2>
            )}
            {subtitle && <p className="mt-0.5 text-sm text-ink-muted">{subtitle}</p>}
          </div>
          {actions}
        </header>
      )}
      <div className={flush ? "" : "px-4 pb-6 sm:px-6"}>{children}</div>
    </section>
  );
}

/**
 * A headline metric.
 *
 * The value is deliberately large — this style of product leads with the number and treats
 * the label as secondary, which is the opposite of a typical dashboard tile.
 */
export function StatCard({
  label,
  value,
  hint,
  tone = "default",
}: {
  label: string;
  value: ReactNode;
  hint?: string;
  tone?: "default" | "good" | "bad" | "warn" | "brand";
}) {
  const toneClass = {
    default: "text-ink",
    good: "text-positive",
    bad: "text-negative",
    warn: "text-caution",
    brand: "text-brand-700",
  }[tone];

  return (
    <div className="rounded-2xl border border-line bg-surface p-5 shadow-card sm:p-6">
      <p className="text-xs font-medium uppercase tracking-wider text-ink-muted">
        {label}
      </p>
      <p className={cx("tnum mt-2 text-3xl font-semibold tracking-tight", toneClass)}>
        {value}
      </p>
      {hint && <p className="mt-1.5 text-xs text-ink-faint">{hint}</p>}
    </div>
  );
}

export function Button({
  children,
  variant = "primary",
  size = "md",
  className,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost" | "danger";
  size?: "sm" | "md" | "lg";
}) {
  const variants = {
    primary:
      "bg-brand-600 text-white border-transparent hover:bg-brand-700 shadow-brand",
    secondary:
      "bg-surface text-ink border-line-strong hover:bg-surface-muted hover:border-ink-faint",
    ghost:
      "bg-transparent text-ink-soft border-transparent hover:bg-surface-sunken hover:text-ink",
    danger: "bg-negative text-white border-transparent hover:opacity-90",
  };
  const sizes = {
    sm: "min-h-10 px-3 py-1.5 text-xs",
    md: "min-h-11 px-4 py-2 text-sm",
    lg: "min-h-12 px-6 py-3 text-base",
  };
  return (
    <button
      {...rest}
      className={cx(
        "inline-flex items-center justify-center gap-2 rounded-full border font-medium transition-all duration-150 disabled:cursor-not-allowed disabled:opacity-45 disabled:shadow-none",
        variants[variant],
        sizes[size],
        className
      )}
    >
      {children}
    </button>
  );
}

export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-sm font-medium text-ink-soft">{label}</span>
      {children}
      {hint && <span className="mt-1.5 block text-xs text-ink-faint">{hint}</span>}
    </label>
  );
}

const inputClass =
  "min-h-11 w-full rounded-xl border border-line-strong bg-surface px-3.5 py-2.5 text-sm text-ink placeholder:text-ink-faint transition focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-100";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(inputClass, props.className)} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(inputClass, props.className)} />;
}

export function Badge({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <span
      className={cx(
        "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium",
        className ?? "border-line bg-surface-sunken text-ink-soft"
      )}
    >
      {children}
    </span>
  );
}

export function ErrorNote({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p role="alert" className="rounded-xl border border-negative/25 bg-negative/[0.06] px-4 py-3 text-sm text-negative">
      {message}
    </p>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="py-12 text-center text-sm text-ink-muted">{children}</div>
  );
}

/** Section divider used inside cards, where a full border would be too heavy. */
export function Divider({ className }: { className?: string }) {
  return <div className={cx("h-px w-full bg-line", className)} />;
}
