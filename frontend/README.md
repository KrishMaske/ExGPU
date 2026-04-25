# ExGPU frontend

Production-oriented Next.js, TypeScript, and Tailwind frontend for the ExGPU GPU Compute Exchange. The public experience is a white-and-purple marketplace for live, time-windowed GPU capacity; authenticated routes cover renting, providing, access, cancellations, and billing.

## Routes

| Route | Purpose |
| --- | --- |
| `/` | Public marketplace, compatible-supply search, and provider overview |
| `/login`, `/signup` | Supabase email/password authentication |
| `/app` | Account overview, balance, rentals, supply, and activity |
| `/app/rent` | Browse supply or place a custom buy order |
| `/app/rentals` | Rental lifecycle, access credentials, cancellations, and waiting orders |
| `/app/provide` | Fill demand, list capacity, and review provider activity |
| `/app/billing` | Token balance, direct-credit funding, and ledger |
| `/diagnostics` | Public configuration and backend diagnostics |

## Development

```powershell
npm install
npm run dev
```

The development server runs at `http://localhost:3001`. Development defaults are `http://localhost:8080` for the REST API and `ws://localhost:8080/ws` for WebSocket/STOMP updates.

Create `.env.local` with Supabase credentials and any local overrides:

```dotenv
NEXT_PUBLIC_SITE_URL=http://localhost:3001
NEXT_PUBLIC_API_BASE=http://localhost:8080
NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=your-anon-key
```

## Production

All five variables above are required in production. `NEXT_PUBLIC_*` values are embedded into browser bundles during the build, so changing them requires a rebuild. Production does not fall back to localhost and its Content Security Policy excludes development-only `unsafe-eval`.

Node.js 20.9 or newer is required. Validate every release separately because the production build does not run lint automatically:

```powershell
npm run lint
npm run typecheck
npm run build
npm audit --omit=dev --audit-level=high
npm run start
```

ExGPU remains a systems demo. Compute, access, and direct-credit funding are simulated. Marketplace matching, order lifecycle, billing rules, and telemetry are implemented.
