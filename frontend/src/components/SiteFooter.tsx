"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { authHref } from "@/lib/navigation";
import { Logo } from "./Logo";

export function SiteFooter() {
  const { user } = useAuth();
  const rentHref = user ? "/app/rent" : authHref("/login", "/app/rent");
  const provideHref = user ? "/app/provide" : authHref("/signup", "/app/provide");
  return (
    <footer className="border-t border-line bg-surface-muted">
      <div className="mx-auto grid max-w-7xl gap-10 px-4 py-14 sm:grid-cols-2 sm:px-6 lg:grid-cols-[2fr_1fr_1fr_1fr] lg:px-8">
        <div className="max-w-md">
          <Logo />
          <p className="mt-4 text-sm leading-6 text-ink-muted">ExGPU is a systems demo. Compute, access, and direct-credit funding are simulated; marketplace matching, lifecycle, billing rules, and telemetry are implemented.</p>
        </div>
        <FooterColumn title="Marketplace">
          <FooterLink href="/#marketplace">Browse compute</FooterLink>
          <FooterLink href={rentHref}>Rent compute</FooterLink>
          <FooterLink href={provideHref}>Provide capacity</FooterLink>
        </FooterColumn>
        <FooterColumn title="Account">
          {user ? <><FooterLink href="/app">Dashboard</FooterLink><FooterLink href="/app/rentals">My rentals</FooterLink><FooterLink href="/app/billing">Billing</FooterLink></> : <><FooterLink href="/login">Sign in</FooterLink><FooterLink href="/signup">Create account</FooterLink></>}
          <FooterLink href="/diagnostics">Diagnostics</FooterLink>
        </FooterColumn>
        <FooterColumn title="Learn">
          <FooterLink href="/#how-it-works">How it works</FooterLink>
          <FooterLink href="/#workloads">Workloads</FooterLink>
          <FooterLink href="/#providers">For providers</FooterLink>
        </FooterColumn>
      </div>
      <div className="border-t border-line px-4 py-6 text-center text-xs text-ink-muted">© {new Date().getFullYear()} ExGPU. GPU Compute Exchange.</div>
    </footer>
  );
}

function FooterColumn({ title, children }: { title: string; children: React.ReactNode }) {
  return <div><h2 className="text-sm font-semibold text-ink">{title}</h2><div className="mt-4 grid gap-3 text-sm">{children}</div></div>;
}

function FooterLink({ href, children }: { href: string; children: React.ReactNode }) {
  return <Link href={href} className="w-fit text-ink-muted transition hover:text-brand-700">{children}</Link>;
}
