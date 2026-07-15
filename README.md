# LifeOS

**Ton téléphone te sert. Pas l'inverse.**

LifeOS est un launcher Android qui remplace ton écran d'accueil et **verrouille physiquement ton
téléphone** pendant tes blocs de focus. Planning, notes, objectifs, streak — un seul système,
synchronisé entre ton téléphone et ton PC.

→ **[Télécharger l'APK](https://enzoblgz.github.io/lifeos/download/lifeos.apk)** · **[Version PC](https://enzoblgz.github.io/lifeos/app/)** · **[Site](https://enzoblgz.github.io/lifeos/)**

---

## Le cœur : le verrou de focus

Tu choisis un bloc, tu entres en focus. À partir de là, chaque tentative d'ouvrir une autre appli
te ramène à l'écran de blocage — et elle est comptée. La seule sortie : recopier ton serment
d'honneur, qui coche le bloc automatiquement.

## Fonctions

- **Planning en blocs** cochables, avec non-négociables (« NN »), calendrier et récurrences.
- **Notes** riches : texte, photo, vidéo, mémo vocal, dessin. Enregistrement automatique, corbeille 30 jours.
- **Widgets** Android sur une grille libre qui se compacte toute seule.
- **Objectifs par horizon** (semaine / mois / année / long terme) avec étapes cochables.
- **Streak** : jour complet = tous les blocs NN cochés, sinon retour à zéro.
- **Launcher complet** : applis, dossiers, désinstallation, raccourcis, liste blanche de focus.
- **Sync** téléphone ↔ PC en quelques secondes.

## Architecture

| Couche | Rôle |
|---|---|
| **GitHub** | Code source + hébergement du site (**GitHub Pages**, dossier `docs/`) |
| **Supabase** | Authentification, base de données, synchronisation d'état |

```
android/   App native Kotlin + Jetpack Compose (le launcher)
docs/      Site public (index.html) + interface PC (app/) + APK (download/) — servi par GitHub Pages
supabase/  Schéma SQL de la base
sync/      Notes techniques de synchronisation
```

Le site est publié par **GitHub Pages** depuis le dossier `docs/` de la branche `main`
(Settings → Pages → Source : `main` / `/docs`) → https://enzoblgz.github.io/lifeos/

## Construire l'app

Depuis `android/` :

```bash
gradle assembleDebug
# APK → android/app/build/outputs/apk/debug/app-debug.apk
```

Min SDK 26 (Android 8.0). Voir `android/README.md` pour l'installation et les autorisations.

## Configurer Supabase

Renseigner l'URL du projet et la clé **anon** (publique) dans :
- `android/app/src/main/java/fr/bellenguez/lifeos/Supa.kt`
- `docs/app/index.html`

Créer une table `user_state (user_id uuid, data jsonb, updated_at timestamptz)` avec RLS par
utilisateur. Voir `supabase/schema.sql`.

## Licence

Projet personnel d'[Enzo Bellenguez](https://enzo.bellenguez.fr), ouvert au public.
