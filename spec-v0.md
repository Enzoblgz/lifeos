# LifeOS — Spec v0

> Projet : [[1-PROJETS/LifeOS/_contexte]] · Casquette : [[2-CASQUETTES/Businessman/_contexte]]

## Principe

Le vault Obsidian `para/` définit le système (casquettes, projets, planning, streak). LifeOS est l'**interface d'exécution** : ce qu'on ouvre 10 fois par jour pour cocher, vérifier, avancer. Pas un deuxième cerveau — un cockpit.

## Utilisateur & contexte d'usage

- Ordi (Mac) : le matin pour voir le plan, le soir pour le bilan
- Téléphone (Android) : dans la journée, cocher les blocs en 5 secondes
- Été 2026 : boulot 7h00–12h30 lun-ven, sport quotidien, prépa prépa l'après-midi
- À partir de septembre : internat Lakanal — l'app doit survivre au changement de rythme (emplois du temps par "saison")

## Écrans (v0)

### 1. Aujourd'hui (écran d'accueil)
- Date + streak en haut
- Liste des blocs du jour, générés depuis l'emploi du temps de la saison active (été / internat)
- Chaque bloc : heure, titre, tag casquette, badge NON NÉGOCIABLE si niveau 1, checkbox
- MVD (Minimum Viable Day) : les 3 blocs qui suffisent à valider la journée
- Barre de progression du jour

### 2. Semaine
- Objectifs hebdo avec quota (ex : 5 séances sport, 10h prépa) et progression
- Calcul automatique : reste à faire ÷ jours restants = charge du jour
- Alerte si un objectif est en retard (règle : retard → priorité haute)

### 3. Projets
- Miroir de CLAUDE.md §5 : projets actifs, casquette, statut, prochaine action
- Tap sur un projet → note rapide (capture inbox)

### 4. Streak
- Compteur de jours complets
- Règle : journée validée = blocs non-négociables cochés
- Conséquences affichées si jour raté (+15 min renfo, cumul max +45)

## Règles système embarquées (héritées de CLAUDE.md)

1. Blocs niveau 1 non déplaçables, non supprimables dans l'app
2. Jamais deux blocs intensifs consécutifs sans pause
3. Chaque tâche doit pointer vers un objectif hebdo ou un projet
4. Fin de journée : mini-review (fait / pas fait / ajustement demain)

## Architecture

```
v0 (maintenant)     : 1 fichier HTML, localStorage, données codées en dur
v1 (sync réelle)    : PWA + Supabase (auth simple, table days/blocks/goals)
v2 (source vault)   : import weekly-goals.md et streak.md via Google Drive API
```

**Pourquoi PWA et pas React Native** : PeakRoutine (RN) est en pause ; une PWA = un seul déploiement pour Mac + Android, installable depuis Chrome ("Ajouter à l'écran d'accueil"), et réutilise directement les compétences HTML/CSS/JS + le pipeline Vercel du site perso.

**Sync v1** : Supabase en temps réel — cocher sur le téléphone, l'ordi se met à jour. Modèle de données minimal :
- `seasons` (été-2026, internat-2026…) → blocs fixes
- `days` (date, blocs cochés, jour complet oui/non)
- `weekly_goals` (semaine, objectif, quota, fait)
- `projects` (nom, casquette, statut, next action)

## Ce que l'app ne fait PAS

- Pas de prise de notes longue (ça reste Obsidian)
- Pas de Garden / atomic notes (ça reste `/garden-review`)
- Pas de planification IA — elle affiche le plan, Claude Code le construit

## Prototype

`prototype/index.html` — ouvrir dans un navigateur. Données réelles de l'été 2026, coches persistées en localStorage (par appareil pour l'instant).
