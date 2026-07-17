-- Rapports de crash de l'app Android (envoyés par CrashReporter.kt).
-- À coller dans le SQL Editor Supabase (une seule fois).
-- Lecture : uniquement depuis le dashboard Supabase (Table Editor) — aucun accès en lecture pour les clés publiques.

create table if not exists public.crash_reports (
  id bigint generated always as identity primary key,
  created_at timestamptz not null default now(),
  version text,
  device text,
  stack text
);

alter table public.crash_reports enable row level security;

-- insert-only pour tout le monde (anon + connectés) : un client peut déposer un rapport, jamais lire ceux des autres
create policy "crash insert" on public.crash_reports
  for insert to anon, authenticated with check (true);
