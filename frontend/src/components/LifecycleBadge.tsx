import type { Lifecycle } from "@/lib/types";
import { Badge } from "./ui";

const STYLES: Record<Lifecycle, { className: string; label: string }> = {
  RUNNING: {
    className: "border-positive/25 bg-positive/[0.08] text-positive",
    label: "Running",
  },
  SCHEDULED: {
    className: "border-brand-200 bg-brand-50 text-brand-700",
    label: "Scheduled",
  },
  ENDED: {
    className: "border-line bg-surface-sunken text-ink-muted",
    label: "Ended",
  },
};

/** Window state as a chip. The backend derives lifecycle from the clock. */
export function LifecycleBadge({ lifecycle }: { lifecycle: Lifecycle }) {
  const style = STYLES[lifecycle] ?? STYLES.ENDED;
  return <Badge className={style.className}>{style.label}</Badge>;
}
