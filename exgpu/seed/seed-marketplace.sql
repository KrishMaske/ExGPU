-- Seeds a browsable marketplace for local development.
--
-- WHY THIS EXISTS
-- Orders age out. OrderExpiryScheduler retires anything whose window has passed, so a
-- database that was full a day ago serves an empty /market/supply today, and the whole
-- product renders as empty states. That reads as "the site is broken" when nothing is
-- broken — there is simply nothing for sale. This script refills the book with plausible
-- inventory anchored to now(), so the windows are always in the future.
--
-- IDEMPOTENT
-- Every row is owned by one of the four synthetic provider/buyer UUIDs below. The script
-- deletes exactly those rows first, so re-running it refreshes the catalogue rather than
-- stacking duplicates, and it can never touch a real user's orders.
--
-- SAFETY
-- orders.owner_id has no foreign key to an identity table (Supabase owns identity), so these
-- UUIDs belong to nobody. That is deliberate: the matching engine refuses self-trades, so
-- listings owned by a *real* account would be invisible to that same account. Owned by
-- nobody, every signed-in user can rent all of them.
--
-- USAGE
--   docker exec -i exgpu-postgres psql -U exgpu -d exgpu < exgpu/seed/seed-marketplace.sql
--
-- Then restart the Spring app. The in-memory order book is rebuilt from Postgres only at
-- ApplicationReadyEvent (OrderBookRehydrator), so until a restart these rows are visible on
-- the marketplace but not yet matchable by the engine.

BEGIN;

-- Allocations cascade from orders, so clear any prior seeded trades first by deleting the
-- orders themselves; FK ON DELETE CASCADE handles the rest.
DELETE FROM orders
WHERE owner_id IN (
  '00000000-0000-4000-a000-000000000001',  -- provider: "dense" nodes, 8-16 GPUs
  '00000000-0000-4000-a000-000000000002',  -- provider: mid-size, 4-8 GPUs
  '00000000-0000-4000-a000-000000000003',  -- provider: small/cheap, 1-4 GPUs
  '00000000-0000-4000-a000-000000000004'   -- buyer: open demand, for the Provide page
);

-- ---------------------------------------------------------------------------------------
-- SUPPLY — what buyers browse on /app/rent.
--
-- Spread deliberately across three axes so the filters and chips have something to bite on:
-- price bands straddle the $1/$2/$3 cutoffs, quantities straddle the 1-4 / 5-8 / 8+ tiers,
-- and windows straddle "available now", "today" and "later this week". A catalogue where
-- every card is similar makes the filtering look broken even when it works.
-- ---------------------------------------------------------------------------------------
INSERT INTO orders (owner_id, side, status, price_per_gpu_hr, quantity, filled_quantity,
                    window_start, window_end, priority_timestamp)
VALUES
  -- Available now / starting within the hour
  ('00000000-0000-4000-a000-000000000003', 'SELL', 'OPEN', 0.85,  2, 0, now() - interval '10 min', now() + interval '3 hour',  now() - interval '3 hour'),
  ('00000000-0000-4000-a000-000000000002', 'SELL', 'OPEN', 1.90,  6, 0, now() + interval '20 min', now() + interval '4 hour',  now() - interval '2 hour'),
  ('00000000-0000-4000-a000-000000000001', 'SELL', 'OPEN', 3.10, 16, 0, now() + interval '45 min', now() + interval '7 hour',  now() - interval '5 hour'),
  ('00000000-0000-4000-a000-000000000003', 'SELL', 'OPEN', 1.25,  1, 0, now() + interval '30 min', now() + interval '90 min',  now() - interval '1 hour'),

  -- Later today
  ('00000000-0000-4000-a000-000000000002', 'SELL', 'OPEN', 2.40,  8, 0, now() + interval '3 hour',  now() + interval '9 hour',  now() - interval '4 hour'),
  ('00000000-0000-4000-a000-000000000001', 'SELL', 'OPEN', 2.95, 12, 0, now() + interval '5 hour',  now() + interval '13 hour', now() - interval '6 hour'),
  ('00000000-0000-4000-a000-000000000003', 'SELL', 'OPEN', 0.95,  4, 0, now() + interval '6 hour',  now() + interval '8 hour',  now() - interval '90 min'),
  ('00000000-0000-4000-a000-000000000002', 'SELL', 'OPEN', 1.75,  5, 0, now() + interval '8 hour',  now() + interval '12 hour', now() - interval '30 min'),

  -- Tomorrow
  ('00000000-0000-4000-a000-000000000001', 'SELL', 'OPEN', 4.50, 16, 0, now() + interval '1 day',   now() + interval '1 day 6 hour',  now() - interval '7 hour'),
  ('00000000-0000-4000-a000-000000000002', 'SELL', 'OPEN', 2.20,  8, 0, now() + interval '1 day 2 hour', now() + interval '1 day 10 hour', now() - interval '8 hour'),
  ('00000000-0000-4000-a000-000000000003', 'SELL', 'OPEN', 1.10,  3, 0, now() + interval '1 day 4 hour', now() + interval '1 day 7 hour',  now() - interval '20 min'),

  -- Later this week — the "plan ahead" end of the book
  ('00000000-0000-4000-a000-000000000001', 'SELL', 'OPEN', 3.75, 10, 0, now() + interval '2 day',  now() + interval '2 day 8 hour',  now() - interval '9 hour'),
  ('00000000-0000-4000-a000-000000000002', 'SELL', 'OPEN', 2.60,  6, 0, now() + interval '3 day',  now() + interval '3 day 5 hour',  now() - interval '10 hour'),
  ('00000000-0000-4000-a000-000000000003', 'SELL', 'OPEN', 0.80,  2, 0, now() + interval '4 day',  now() + interval '4 day 2 hour',  now() - interval '11 hour'),

  -- One partially filled listing, so the "remaining quantity" path is exercised in the UI.
  ('00000000-0000-4000-a000-000000000001', 'SELL', 'PARTIALLY_FILLED', 2.05, 12, 5, now() + interval '10 hour', now() + interval '16 hour', now() - interval '12 hour');

-- ---------------------------------------------------------------------------------------
-- DEMAND — open buy requests, which is what providers browse on /app/provide.
-- Owned by a fourth synthetic account so a signed-in provider can fill them (the engine
-- blocks self-trades, and these belong to nobody).
-- ---------------------------------------------------------------------------------------
INSERT INTO orders (owner_id, side, status, price_per_gpu_hr, quantity, filled_quantity,
                    window_start, window_end, priority_timestamp)
VALUES
  ('00000000-0000-4000-a000-000000000004', 'BUY', 'OPEN', 3.50,  4, 0, now() + interval '2 hour',  now() + interval '6 hour',  now() - interval '2 hour'),
  ('00000000-0000-4000-a000-000000000004', 'BUY', 'OPEN', 2.75,  8, 0, now() + interval '40 min',  now() + interval '5 hour',  now() - interval '3 hour'),
  ('00000000-0000-4000-a000-000000000004', 'BUY', 'OPEN', 5.00, 16, 0, now() + interval '1 day',   now() + interval '1 day 8 hour', now() - interval '4 hour'),
  ('00000000-0000-4000-a000-000000000004', 'BUY', 'OPEN', 1.60,  2, 0, now() + interval '7 hour',  now() + interval '9 hour',  now() - interval '1 hour'),
  ('00000000-0000-4000-a000-000000000004', 'BUY', 'OPEN', 4.20, 12, 0, now() + interval '2 day',   now() + interval '2 day 6 hour', now() - interval '5 hour');

COMMIT;

-- What the marketplace now holds.
SELECT side, status, count(*) AS orders, sum(quantity - filled_quantity) AS gpus_available,
       min(price_per_gpu_hr) AS cheapest, max(price_per_gpu_hr) AS dearest
FROM orders
WHERE status IN ('OPEN', 'PARTIALLY_FILLED') AND window_end > now()
GROUP BY side, status
ORDER BY side, status;
