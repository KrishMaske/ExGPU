"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { authHref } from "@/lib/navigation";
import { Logo } from "./Logo";
import { ThemeToggle } from "@/lib/theme";

export function SiteHeader() {
  const { user, loading } = useAuth();
  const [open, setOpen] = useState(false);
  const headerRef = useRef<HTMLElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const provideHref = user ? "/app/provide" : authHref("/signup", "/app/provide");

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      if (!headerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <header ref={headerRef} className="sticky top-0 z-40 border-b border-line bg-surface/90 backdrop-blur-xl">
      <div className="mx-auto flex min-h-20 max-w-7xl items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
        <Link href="/" aria-label="ExGPU home" className="shrink-0">
          <Logo priority />
        </Link>
        <nav aria-label="Primary" className="hidden items-center gap-1 md:flex">
          <HeaderLink href="/#marketplace">Rent compute</HeaderLink>
          <HeaderLink href="/#how-it-works">How it works</HeaderLink>
          <HeaderLink href={provideHref}>Provide capacity</HeaderLink>
        </nav>
        <div className="flex items-center gap-2">
          <ThemeToggle className="flex h-11 w-11 items-center justify-center rounded-full text-ink-muted transition hover:bg-surface-muted hover:text-ink" />
          {!loading && (user ? (
            <Link href="/app" className="inline-flex min-h-11 items-center rounded-full bg-brand-600 px-5 text-sm font-semibold text-white shadow-brand transition hover:bg-brand-700">Dashboard</Link>
          ) : (
            <>
              <Link href="/login" className="hidden min-h-11 items-center rounded-full px-4 text-sm font-semibold text-ink-soft transition hover:bg-surface-muted hover:text-ink sm:inline-flex">Sign in</Link>
              <Link href="/signup" className="inline-flex min-h-11 items-center rounded-full bg-brand-600 px-5 text-sm font-semibold text-white shadow-brand transition hover:bg-brand-700">Get started</Link>
            </>
          ))}
          <button
            ref={triggerRef}
            type="button"
            aria-label="Open navigation menu"
            aria-expanded={open}
            aria-controls="mobile-navigation"
            onClick={() => setOpen((value) => !value)}
            className="flex h-11 w-11 items-center justify-center rounded-full border border-line-strong text-xl text-ink md:hidden"
          >
            {open ? "×" : "≡"}
          </button>
        </div>
      </div>
      {open && (
        <nav id="mobile-navigation" aria-label="Mobile primary" className="border-t border-line bg-surface px-4 py-3 md:hidden">
          <div className="mx-auto grid max-w-7xl gap-1">
            <MobileLink href="/#marketplace" close={() => setOpen(false)}>Rent compute</MobileLink>
            <MobileLink href="/#how-it-works" close={() => setOpen(false)}>How it works</MobileLink>
            <MobileLink href={provideHref} close={() => setOpen(false)}>Provide capacity</MobileLink>
            {!user && <MobileLink href="/login" close={() => setOpen(false)}>Sign in</MobileLink>}
          </div>
        </nav>
      )}
    </header>
  );
}

function HeaderLink({ href, children }: { href: string; children: React.ReactNode }) {
  return <Link href={href} className="inline-flex min-h-11 items-center rounded-full px-4 text-sm font-medium text-ink-soft transition hover:bg-brand-50 hover:text-brand-700">{children}</Link>;
}

function MobileLink({ href, children, close }: { href: string; children: React.ReactNode; close: () => void }) {
  return <Link href={href} onClick={close} className="flex min-h-11 items-center rounded-xl px-3 font-medium text-ink-soft hover:bg-brand-50 hover:text-brand-700">{children}</Link>;
}
