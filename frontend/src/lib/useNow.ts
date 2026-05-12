"use client";

import { useEffect, useState } from "react";

/**
 * The current time, as a value the render is allowed to read.
 *
 * <p>Calling {@code Date.now()} while rendering breaks two things at once. It is impure, so
 * React may re-render and silently get a different answer than the one the output was derived
 * from; and on a server-rendered page it produces a different timestamp on each side of
 * hydration, which is a mismatch React cannot patch up. The browse grids need "is this
 * starting soon?" during render, so the clock has to become an input instead.
 *
 * <p>Returns 0 until mounted. Every caller renders a loading state until its data arrives —
 * which is necessarily after mount — so no real UI is ever derived from the zero.
 *
 * <p>Ticking matters: a listing whose window opens in two minutes should pick up its
 * "Available now" badge without a reload. A minute is fine for that, and is cheap.
 */
export function useNow(intervalMs = 60_000): number {
  const [now, setNow] = useState(0);

  useEffect(() => {
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);

  return now;
}
