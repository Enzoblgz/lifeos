-- LifeOS — schéma Supabase v1
-- À exécuter dans le SQL Editor du projet (supabase.com → SQL Editor → coller → Run)

-- Un blob d'état par utilisateur. Simple, robuste, temps réel.
create table if not exists public.user_state (
  user_id uuid primary key references auth.users (id) on delete cascade,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

alter table public.user_state enable row level security;

create policy "chacun son état — lecture"
  on public.user_state for select
  using (auth.uid() = user_id);

create policy "chacun son état — écriture"
  on public.user_state for insert
  with check (auth.uid() = user_id);

create policy "chacun son état — mise à jour"
  on public.user_state for update
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

drop trigger if exists user_state_touch on public.user_state;
create trigger user_state_touch
  before update on public.user_state
  for each row execute function public.touch_updated_at();

-- Temps réel : le web se met à jour quand le téléphone pousse (et inversement)
alter publication supabase_realtime add table public.user_state;
