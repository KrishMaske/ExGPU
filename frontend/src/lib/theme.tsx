"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

export type Theme = "light" | "dark" | "system";

const STORAGE_KEY = "exgpu-theme";

/**
 * Runs before first paint, inlined into <head>.
 *
 * <p>Theme cannot be decided on the server: the choice lives in localStorage and the fallback
 * is the OS preference, neither of which exists during SSR. Without this, every dark-mode user
 * gets a white flash on each navigation while React hydrates and then corrects the attribute.
 * Keeping it tiny and synchronous is the whole point — it must finish before the first paint.
 *
 * <p>Wrapped in try/catch because reading localStorage throws outright in some privacy modes,
 * and a theme preference is never worth breaking the page over.
 */
export const themeScript = `(function(){try{var t=localStorage.getItem("${STORAGE_KEY}");var d=window.matchMedia("(prefers-color-scheme: dark)").matches;var r=document.documentElement;if(t==="dark"||t==="light"){r.setAttribute("data-theme",t);}else{r.setAttribute("data-theme",d?"dark":"light");}}catch(e){}})();`;

interface ThemeContextValue {
  /** What the user chose, including "system". */
  theme: Theme;
  /** What is actually on screen right now — "system" already resolved. */
  resolved: "light" | "dark";
  setTheme: (t: Theme) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: "system",
  resolved: "light",
  setTheme: () => {},
});

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}

function systemPrefersDark(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-color-scheme: dark)").matches
  );
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  // "system" until mounted. The inline script has already painted the right colours, so this
  // placeholder is never visible — it only keeps the server and client markup identical.
  const [theme, setThemeState] = useState<Theme>("system");
  const [resolved, setResolved] = useState<"light" | "dark">("light");

  const apply = useCallback((next: Theme) => {
    const isDark = next === "dark" || (next === "system" && systemPrefersDark());
    document.documentElement.setAttribute("data-theme", isDark ? "dark" : "light");
    setResolved(isDark ? "dark" : "light");
  }, []);

  useEffect(() => {
    let stored: Theme = "system";
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw === "light" || raw === "dark") stored = raw;
    } catch {
      /* storage unavailable — fall back to system */
    }
    setThemeState(stored);
    apply(stored);
  }, [apply]);

  // Follow the OS while the choice is "system"; a user who never picked a theme should see
  // their machine switch to dark at sunset without reloading the tab.
  useEffect(() => {
    if (theme !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => apply("system");
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [theme, apply]);

  const setTheme = useCallback(
    (next: Theme) => {
      setThemeState(next);
      apply(next);
      try {
        if (next === "system") localStorage.removeItem(STORAGE_KEY);
        else localStorage.setItem(STORAGE_KEY, next);
      } catch {
        /* preference simply will not persist */
      }
    },
    [apply]
  );

  return (
    <ThemeContext.Provider value={{ theme, resolved, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

/** Sun/moon toggle. Flips between light and dark; long-standing "system" resolves on click. */
export function ThemeToggle({ className }: { className?: string }) {
  const { resolved, setTheme } = useTheme();
  const next = resolved === "dark" ? "light" : "dark";

  return (
    <button
      type="button"
      onClick={() => setTheme(next)}
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
      className={
        className ??
        "flex h-11 w-11 items-center justify-center rounded-full border border-line bg-surface text-ink-muted transition hover:border-ink-faint hover:text-ink"
      }
    >
      {resolved === "dark" ? (
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden>
          <circle cx="12" cy="12" r="4.2" stroke="currentColor" strokeWidth="1.7" />
          <path
            d="M12 2.6v2.2M12 19.2v2.2M21.4 12h-2.2M4.8 12H2.6M18.6 5.4l-1.6 1.6M7 17l-1.6 1.6M18.6 18.6 17 17M7 7 5.4 5.4"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
          />
        </svg>
      ) : (
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path
            d="M20.5 14.4A8.6 8.6 0 1 1 9.6 3.5a7 7 0 0 0 10.9 10.9Z"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinejoin="round"
          />
        </svg>
      )}
    </button>
  );
}
