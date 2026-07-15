# LifeOS — App de gestion de vie

## Description
Application personnelle type Pronote pour piloter la vie d'Enzo au quotidien, synchronisée entre ordinateur et téléphone. Elle reflète exactement le système Obsidian du vault `para/` : casquettes, projets, planning journalier, streak, objectifs hebdomadaires. Le vault reste la source de vérité — l'app en est l'interface mobile/rapide.

## Casquette parente
[[2-CASQUETTES/Businessman/_contexte]]

## Pourquoi ce projet
- Obsidian mobile est lourd pour un usage rapide (cocher un bloc, voir le plan du jour)
- Besoin d'une vue "dashboard" : aujourd'hui + streak + quotas semaine en un écran
- Continuité avec les compétences existantes : HTML/CSS/JS, Supabase ([[1-PROJETS/_en-pause]] Agora), déploiement (enzo.bellenguez.fr)

## Vision produit
Écrans principaux :
1. **Aujourd'hui** — plan du jour en blocs cochables (généré depuis l'emploi du temps fixe + objectifs)
2. **Semaine** — objectifs hebdo avec quotas et progression (weekly-goals)
3. **Projets** — les projets actifs de [[CLAUDE]] §5, statut et prochaine action
4. **Streak** — compteur jours complets + conséquences (miroir de streak.md)

## Stack (décision 13/07 — pivot natif)
- **App Android native : Kotlin + Jetpack Compose** (`android/`) — choix imposé par le besoin de contrôle total du téléphone (une web app ne peut pas bloquer les autres apps)
- Verrouillage : service de premier plan + UsageStatsManager (détection de l'app active) + permission de superposition → toute fuite est rattrapée en < 1 s et comptée
- Design : noir et blanc strict, typographie lourde, zéro couleur, zéro distraction
- Données v0.1 : SharedPreferences local → v1 : Supabase (sync Mac/téléphone)
- Le prototype web (`prototype/index.html`) reste comme référence design

## Fonctionnalités v0.1 (livrées)
- **Mode FOCUS** : plein écran verrouillé sur la tâche de l'instant T, chrono, compteur de fuites
- **Certification sur l'honneur** : déblocage uniquement en recopiant « Je certifie sur l'honneur avoir accompli cette tâche. » — coche le bloc automatiquement
- **VOIR POURQUOI** : arsenal de motivation (textes, images, vidéos — rendus en niveaux de gris) consultable pendant le blocage
- 5 onglets : NOW / SEMAINE (quotas + alerte retard) / PROJETS / POURQUOI / STREAK

## v1 — Plateforme multi-comptes (13/07 après-midi)
Décision : LifeOS devient une plateforme type Instagram — comptes utilisateurs, mêmes données sur téléphone et ordi.
- **Supabase** au centre : auth email/mot de passe + table `user_state` (un blob JSONB par utilisateur, RLS, temps réel) — schéma dans `supabase/schema.sql`
- **App Android** = client : écran de connexion, pull au lancement, push automatique après chaque changement
- **App web** (`web/index.html`) = client ordi : mêmes écrans + **éditeur d'emploi du temps et de quotas** (modifs poussées vers le téléphone en temps réel) — à déployer sur Vercel
- **Liste blanche du focus** : onglet APPS dans l'app — cocher les apps qui restent accessibles pendant le verrouillage
- Pont USB conservé : `sync/pull.sh` tire l'état du téléphone dans le vault (`sync/etat-telephone.md`)

## v1.1 — Planning utilisateur + Launcher (13/07 soir)
- **Zéro défaut** : plus aucun planning pré-rempli, l'utilisateur décide tout (Android + web démarrent vides)
- **Onglet PLAN** dans l'app : éditeur de blocs par jour (LUN–VEN / SAM / DIM) + quotas hebdo, synchronisé
- **LifeOS Launcher** : écran d'accueil Android minimaliste — heure, bloc en cours, apps épinglées en texte, dossiers, recherche. Activer via bouton home → LifeOS → Toujours

## État actuel
13/07/2026 : **v1.1 en production.** App + launcher installés sur le téléphone (Samsung A17), Supabase branché (projet `mpivoorbztrqzfbwqlfb`), client web déployé : **https://lifeos-focus.vercel.app**.

## Roadmap
- [x] Projet Supabase + schéma + clés branchées (Android + web)
- [x] APK v1.0 réinstallé — reste : créer le compte Enzo dans l'app
- [x] Web déployé sur Vercel → https://lifeos-focus.vercel.app (sous-domaine enzo.bellenguez.fr à brancher plus tard)
- [ ] Tester le mode FOCUS en réel 1 semaine + remplir le POURQUOI + cocher la liste blanche
- [ ] v1.1 : défauts génériques pour les nouveaux utilisateurs (l'emploi du temps d'Enzo ne doit pas être le template public)
- [ ] v1.1 : médias du POURQUOI dans Supabase Storage (visibles sur le web)
- [ ] v1.2 : streak automatique à minuit + saisons (été/internat)
- [ ] v2 : import du vault (weekly-goals.md, streak.md) via Google Drive API

## Liens
- Système source : [[CLAUDE]], `streak.md`, `weekly-goals.md`, `0-CALENDAR/base.md`
- Projets frères : [[1-PROJETS/Gustos/_contexte]] (même stack de diffusion)






## v3.1 — Passage grand public (15/07) — en ligne ✓

**Architecture publique** (demande Enzo, « on développe au grand public ») :
- **GitHub** = code source → dépôt **privé** `github.com/Enzoblgz/lifeos` (poussé). Privé par défaut = choix de divulgation réversible ; passer public avec `gh repo edit Enzoblgz/lifeos --visibility public` quand tu veux.
- **Vercel** = site + interface PC → projet `lifeos`, déployé en prod. Domaine stable **lifeos-focus.vercel.app**.
- **Supabase** = auth + base + sync (déjà en place, clé anon publique dans `Supa.kt` et `web/app/index.html`).

**Restructuration du web** :
- `web/index.html` = **nouvelle landing page grand public** (noir & blanc, thèse = le verrou de focus, maquette téléphone avec horloge live, sections problème/verrou/fonctions/launcher/install/FAQ/CTA final, plein de CTA de téléchargement). Zéro erreur JS, responsive.
- `web/app/index.html` = l'ancienne interface PC, déplacée sous **/app** (login Supabase intact).
- `web/download/lifeos.apk` = APK servi au téléchargement (type MIME correct, 9,1 Mo). Landing → liens relatifs `/download/lifeos.apk` et `/app`.

**Modif à la volée — compaction des widgets** : la grille ne réserve plus 3 lignes en dur. Elle se cale en haut (retire les lignes vides au-dessus) et s'arrête à la dernière ligne occupée : `rowStart=min(r)`, `rowEnd=max(r+h)`, hauteur = `cellH*(rowEnd-rowStart)`. Un seul widget de 2 de haut → bloc de 2 lignes, plus de place gaspillée. (`LauncherActivity.kt`, testé en compilation ; pas encore vu à l'écran — téléphone débranché.)

- APK **LifeOS-v3.1.apk** (versionCode 4) compilé + installé sur le téléphone avant restructuration web.
- **Reste à faire (Enzo)** : 1) voir la compaction des widgets à l'écran ; 2) brancher un vrai domaine (`lifeos.app` ?) sur le projet Vercel si souhaité ; 3) décider public/privé du dépôt GitHub ; 4) tester le téléchargement + install depuis le site sur un téléphone tiers.

## v3.0 — Apps « comme sur Android » + notes riches (14/07) — installée ✓

**Menu d'appui long sur une app** (accueil, dossier ou liste APPS) en deux blocs :
- *LifeOS* : autoriser en focus · épingler · dossier · retirer de l'accueil
- *Android* : **Désinstaller** (le système confirme ; une app système renvoie vers Infos, seul moyen de la désactiver) · **Raccourcis de l'app** (ceux d'un appui long Android, via LauncherApps) · **Widgets de cette app** · **Infos de l'app** · **Voir dans le store**
- La liste d'apps suit le système en direct (BroadcastReceiver PACKAGE_ADDED/REMOVED) : une app installée apparaît, une app désinstallée est oubliée partout (épingle, dossier, liste blanche focus, raccourci horloge).

**Notes — corbeille de sécurité** : supprimer ne détruit plus rien. La note part dans `notes_trash` (clé synchronisée), restaurable, purgée automatiquement après **30 jours** (au démarrage du launcher). Bouton CORBEILLE dans NOTES, « Vider la corbeille » avec confirmation. Appui long sur une note = corbeille. Même règle sur le web (restaurer / supprimer définitivement).

**Notes — texte, image, vidéo, audio, dessin** : pièces jointes stockées dans la note (`att:[{t,u}]`, t = img/vid/aud/drw).
- Photo et vidéo prises à la caméra (FileProvider `…lifeos.files`, fichiers dans `filesDir/media`), ou choisies dans la galerie (permission d'uri persistée).
- **Mémo vocal** enregistré dans l'app (MediaRecorder AAC/m4a, permission micro demandée au premier usage) + lecteur ▶/❚❚ intégré.
- **Dessin** au doigt sur toile noire, 3 épaisseurs, annuler le trait / tout effacer → exporté en PNG.
- Aperçu image/dessin dans la grille des notes. Médias orphelins (note détruite depuis le web) balayés au démarrage.

**Notes — enregistrement automatique** : plus de bouton Enregistrer. Sauvegarde 600 ms après la dernière frappe ou pièce jointe (indicateur « Enregistré »), plus un enregistrement forcé à la fermeture. Une note vide n'est jamais créée.

> APK : `LifeOS-v3.0.apk` (versionCode 3). Web : corbeille + compteur de pièces jointes ajoutés dans `web/index.html` — **pas encore déployé sur Vercel**.

## v2.0 — VERSION DÉFINITIVE (14/07) — installée + déployée
- Apps autorisées affichées sur l'écran de focus (raccourcis directs)
- Horloge = raccourci configurable (tap → app au choix, appui long → changer)
- Pied d'accueil élargi (zones tactiles pleine largeur)
- Plans types supprimés — chaque jour se planifie seul, « COPIER LA VEILLE » en aide
- Focus : cible = bloc courant non coché, sinon le suivant non coché (sans se soucier de l'heure) ; appui long sur un bloc = focus direct
- Appui long sur un événement = édition (PLAN/calendrier)
- SEMAINE = objectifs par horizon (semaine/mois/année/long terme) avec étapes cochables — clé goals2 synchronisée

## v1.6 — Notes + PLAN unifié + widget Anki (14/07)
- Onglet NOTES façon Google Keep (grille, éditeur, sync web)
- PLAN = calendrier + planning fusionnés : grille du mois → blocs du jour sélectionné + événements (NN, récurrence) + plans types
- Quotas hebdo édités directement dans SEM.
- À VENIR : 3 événements non récurrents futurs max, chronologique
- Balayage gauche→droite corrigé pour les notifications
- Widget Anki réel (AppWidgetHost, un emplacement, CHOISIR/CHANGER sur l'accueil)
- Pied d'accueil : PLAN · SEM. · NOTES · PQ? · APPS · STREAK

## v1.5 — Le launcher EST l'app (13/07 après-midi)
- Plus d'app séparée : sections CAL · PLAN · SEM. · PQ? · APPS · STREAK directement au pied de l'accueil
- Événements du jour fusionnés au plan comme blocs cochables (NN possible sur un événement)
- ~~geste home → retour accueil~~ → fait (referme aussi les sections ouvertes)
- ~~blocs triés par heure~~ → fait
- ~~récurrence calendrier~~ → fait : UNIQUE / JOUR / SEMAINE / MOIS (↻) — reste à enrichir vers Google Calendar (durées personnalisées, rappels/notifications, invités… → roadmap)
- AnkiDroid épinglé en tête d'accueil (accès rapide demandé)
