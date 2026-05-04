/** Shared display formatting. Kept in one place so money and time read the same everywhere. */

/** Token amounts are shown as currency — they are prepaid credits with a 1:1 dollar framing. */
export function money(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const n = typeof value === "string" ? Number(value) : value;
  if (Number.isNaN(n)) return "—";
  return `$${n.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

/** Higher precision for per-second billing amounts, where cents round away the signal. */
export function tokens(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const n = typeof value === "string" ? Number(value) : value;
  if (Number.isNaN(n)) return "—";
  return n.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 6,
  });
}

export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return iso;
  }
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  } catch {
    return iso;
  }
}

/**
 * A window as one readable span. Collapses the date when both ends fall on the same day,
 * which is the common case for a compute booking.
 */
export function fmtWindow(startIso: string, endIso: string): string {
  try {
    const start = new Date(startIso);
    const end = new Date(endIso);
    const sameDay = start.toDateString() === end.toDateString();

    const date = start.toLocaleDateString(undefined, { month: "short", day: "numeric" });
    const t = (d: Date) =>
      d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });

    return sameDay
      ? `${date}, ${t(start)} → ${t(end)}`
      : `${fmtDateTime(startIso)} → ${fmtDateTime(endIso)}`;
  } catch {
    return `${startIso} → ${endIso}`;
  }
}

/** Seconds as a compact human duration: 5400 → "1h 30m". */
export function fmtDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "—";
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return m > 0 ? `${h}h ${m}m` : `${h}h`;
  if (m > 0) return s > 0 ? `${m}m ${s}s` : `${m}m`;
  return `${s}s`;
}

/** Short id for display: keeps a prefix long enough to be distinguishable in a list. */
export function shortId(id: string | null | undefined, head = 8): string {
  if (!id) return "—";
  return id.length > head ? `${id.slice(0, head)}…` : id;
}

/** Datetime-local input value for `n` hours from now, in the user's own timezone. */
export function localDatetimeIn(hoursFromNow: number): string {
  const d = new Date(Date.now() + hoursFromNow * 3600 * 1000);
  // Shift by the timezone offset so toISOString's UTC output reads as local wall-clock,
  // which is what <input type="datetime-local"> expects.
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 16);
}
