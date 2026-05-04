import Link from "next/link";
import { MarketplaceExplorer } from "@/components/MarketplaceExplorer";
import { SiteFooter } from "@/components/SiteFooter";
import { SiteHeader } from "@/components/SiteHeader";

const workloads = [
  ["Model training", "Reserve a precise multi-GPU window for experiments and scheduled training runs."],
  ["Inference", "Match burst capacity to launches, evaluations, and batch workloads."],
  ["Rendering", "Use time-boxed parallel compute for frames and production queues."],
  ["Research", "Set a budget ceiling and let compatible capacity meet your request."],
];

export default function LandingPage() {
  return (
    <div className="min-h-screen bg-surface">
      <SiteHeader />
      <main id="main">
        <section className="relative overflow-hidden">
          <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0 h-[34rem] bg-[radial-gradient(circle_at_50%_10%,rgba(124,58,237,.14),transparent_58%)]" />
          <div className="relative mx-auto max-w-7xl px-4 pb-12 pt-16 text-center sm:px-6 sm:pb-16 sm:pt-24 lg:px-8">
            <p className="mx-auto inline-flex min-h-9 items-center rounded-full border border-brand-200 bg-surface px-4 text-sm font-medium text-brand-700 shadow-card">Live GPU capacity, matched to your window</p>
            <h1 className="mx-auto mt-7 max-w-4xl text-4xl font-semibold tracking-[-0.045em] text-ink sm:text-6xl lg:text-7xl">GPU compute, ready <span className="text-brand-600">when you are.</span></h1>
            <p className="mx-auto mt-6 max-w-2xl text-base leading-7 text-ink-soft sm:text-lg">Browse live capacity or name the quantity, price ceiling, and exact time window you need. ExGPU matches compatible supply without charging for idle hours around your job.</p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Link href="#marketplace" className="inline-flex min-h-11 items-center rounded-full bg-brand-600 px-6 font-medium text-white shadow-brand transition hover:bg-brand-700">Find compute</Link>
              <Link href="/app/provide" className="inline-flex min-h-11 items-center rounded-full border border-line-strong bg-surface px-6 font-medium text-ink transition hover:border-brand-300 hover:bg-brand-50">List capacity</Link>
            </div>
          </div>
        </section>

        <MarketplaceExplorer />

        <section id="workloads" className="border-t border-line py-20 sm:py-24">
          <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
            <p className="text-sm font-semibold uppercase tracking-[.16em] text-brand-600">Built for real workflows</p>
            <h2 className="mt-3 max-w-2xl text-3xl font-semibold tracking-tight text-ink sm:text-4xl">Flexible compute for the work in front of you</h2>
            <p className="mt-4 max-w-2xl leading-7 text-ink-soft">These are common ways to use scheduled GPU capacity, not classifications or guarantees attached to individual listings.</p>
            <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              {workloads.map(([label, text], index) => (
                <article key={label} className="rounded-3xl border border-line bg-surface p-6 shadow-card">
                  <span className="tnum text-sm font-semibold text-brand-600">0{index + 1}</span>
                  <h3 className="mt-8 text-xl font-semibold text-ink">{label}</h3>
                  <p className="mt-3 text-sm leading-6 text-ink-soft">{text}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section id="how-it-works" className="border-y border-line bg-surface-muted py-20 sm:py-24">
          <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
            <div className="mx-auto max-w-2xl text-center">
              <p className="text-sm font-semibold uppercase tracking-[.16em] text-brand-600">How it works</p>
              <h2 className="mt-3 text-3xl font-semibold tracking-tight text-ink sm:text-4xl">From request to access in three clear steps</h2>
            </div>
            <div className="mt-12 grid gap-8 md:grid-cols-3">
              {[
                ["Set your terms", "Choose GPUs, maximum price, and the exact start and end time."],
                ["Match compatible supply", "The exchange fills against listings that cover your quantity, budget, and full window."],
                ["Use only your window", "Access becomes available when the allocation begins and expires when it ends."],
              ].map(([title, text], index) => (
                <article key={title} className="text-center">
                  <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-100 font-semibold text-brand-700">{index + 1}</span>
                  <h3 className="mt-5 text-xl font-semibold text-ink">{title}</h3>
                  <p className="mx-auto mt-3 max-w-sm leading-7 text-ink-soft">{text}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section id="providers" className="py-20 sm:py-24">
          <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
            {/*
              Fixed purple in both themes, so every foreground here is fixed too. brand-700
              inverts between themes (dark purple → light lavender), which would have turned
              the eyebrow and body copy unreadable against the panel; brand-600 is the one step
              of the ramp deliberately held vivid in both, which is why it anchors this block.
            */}
            <div className="relative overflow-hidden rounded-[2rem] bg-brand-600 px-6 py-12 text-white shadow-brand sm:px-12 lg:flex lg:items-center lg:justify-between lg:gap-12">
              <div className="max-w-2xl">
                <p className="text-sm font-semibold uppercase tracking-[.16em] text-white/70">For providers</p>
                <h2 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">Put idle GPU windows to work.</h2>
                <p className="mt-4 leading-7 text-white/85">Set your available quantity, floor price, and schedule. Partial fills let compatible demand use capacity without exposing your identity.</p>
              </div>
              <Link href="/app/provide" className="mt-8 inline-flex min-h-12 shrink-0 items-center rounded-full bg-white px-6 font-semibold text-brand-600 transition hover:bg-white/90 lg:mt-0">Start providing</Link>
            </div>
          </div>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
