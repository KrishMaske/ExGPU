import type { Config } from "tailwindcss";

/**
 * Design tokens.
 *
 * <p>Every colour resolves to a CSS variable holding **space-separated RGB channels**, wrapped
 * as `rgb(var(--x) / <alpha-value>)`. The channel form is what keeps Tailwind's opacity
 * modifiers working — `bg-caution/[0.07]`, `border-negative/25` and friends are used
 * throughout, and they would silently break if the variables held finished `#rrggbb` or
 * `rgb()` strings. The actual values live in globals.css, where light and dark are two
 * definitions of the same names, so a component never learns which theme it is in.
 *
 * <p>`brand` is the purple ramp, used sparingly — primary actions, active navigation, and the
 * numbers that matter — so it keeps its weight. Note that in dark mode the ramp is *flipped*
 * at the ends: `brand-50` stays "the faintest tint of brand" and `brand-700` stays "brand as
 * readable text", which in a dark theme means dark and light respectively.
 */
const withOpacity = (variable: string) => `rgb(var(${variable}) / <alpha-value>)`;

const config: Config = {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  darkMode: ["class", '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: withOpacity("--brand-50"),
          100: withOpacity("--brand-100"),
          200: withOpacity("--brand-200"),
          300: withOpacity("--brand-300"),
          400: withOpacity("--brand-400"),
          500: withOpacity("--brand-500"),
          600: withOpacity("--brand-600"),
          700: withOpacity("--brand-700"),
          800: withOpacity("--brand-800"),
          900: withOpacity("--brand-900"),
        },
        surface: {
          DEFAULT: withOpacity("--surface"),
          muted: withOpacity("--surface-muted"),
          sunken: withOpacity("--surface-sunken"),
        },
        ink: {
          DEFAULT: withOpacity("--ink"),
          soft: withOpacity("--ink-soft"),
          muted: withOpacity("--ink-muted"),
          faint: withOpacity("--ink-faint"),
        },
        line: {
          DEFAULT: withOpacity("--line"),
          strong: withOpacity("--line-strong"),
        },
        positive: withOpacity("--positive"),
        negative: withOpacity("--negative"),
        caution: withOpacity("--caution"),
      },
      fontFamily: {
        sans: [
          "var(--font-sans)",
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "Segoe UI",
          "Roboto",
          "Helvetica Neue",
          "Arial",
          "sans-serif",
        ],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
      },
      fontSize: {
        // Oversized display sizes for the hero numbers this kind of product leads with.
        display: ["3.5rem", { lineHeight: "1.05", letterSpacing: "-0.03em" }],
        hero: ["4.5rem", { lineHeight: "1.02", letterSpacing: "-0.035em" }],
      },
      boxShadow: {
        // Shadows are variables too: a shadow tuned for white paper is invisible on a dark
        // page, where elevation has to come from a lifted surface and a brighter hairline.
        card: "var(--shadow-card)",
        lift: "var(--shadow-lift)",
        brand: "var(--shadow-brand)",
      },
      borderRadius: {
        xl: "0.875rem",
        "2xl": "1.125rem",
      },
      keyframes: {
        "fade-up": {
          from: { opacity: "0", transform: "translateY(6px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        "slide-in": {
          from: { opacity: "0", transform: "translateX(12px)" },
          to: { opacity: "1", transform: "translateX(0)" },
        },
        // The sheet travels its own full width, so it reads as arriving from off-screen
        // rather than fading in place — the difference between a drawer and a popup.
        "slide-over": {
          from: { transform: "translateX(100%)" },
          to: { transform: "translateX(0)" },
        },
        // Mobile shows the same sheet docked to the bottom, so it rises instead.
        "slide-up": {
          from: { transform: "translateY(100%)" },
          to: { transform: "translateY(0)" },
        },
        "fade-in": {
          from: { opacity: "0" },
          to: { opacity: "1" },
        },
        "pop-in": {
          from: { opacity: "0", transform: "scale(0.97)" },
          to: { opacity: "1", transform: "scale(1)" },
        },
        shimmer: {
          "100%": { transform: "translateX(100%)" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.28s ease-out",
        "slide-in": "slide-in 0.2s ease-out",
        "slide-over": "slide-over 0.26s cubic-bezier(0.32, 0.72, 0, 1)",
        "slide-up": "slide-up 0.26s cubic-bezier(0.32, 0.72, 0, 1)",
        "fade-in": "fade-in 0.2s ease-out",
        "pop-in": "pop-in 0.18s cubic-bezier(0.32, 0.72, 0, 1)",
      },
    },
  },
  plugins: [],
};

export default config;
