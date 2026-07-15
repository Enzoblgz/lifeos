# LifeOS — App Android native

App native Kotlin + Jetpack Compose. Design noir et blanc. Verrouillage réel du téléphone pendant les blocs de focus.

## Installer sur le téléphone

**Option A — câble USB :**
1. Sur le téléphone : Paramètres → À propos → tapoter 7× "Numéro de build" → activer "Débogage USB" dans Options développeur
2. Brancher le téléphone au Mac, puis :
   ```
   adb install "1-PROJETS/LifeOS/LifeOS-v0.1.apk"
   ```

**Option B — sans câble :**
Envoyer `LifeOS-v0.1.apk` sur le téléphone (Drive, AirDrop-équivalent, mail), l'ouvrir, autoriser "Installer des apps inconnues".

## Premier lancement

Au premier "ENTRER EN FOCUS", l'app demande 2 autorisations (écrans de réglages ouverts automatiquement) :
1. **Accès aux données d'utilisation** — pour détecter quelle app est au premier plan
2. **Superposition** ("Afficher par-dessus les autres apps") — pour reprendre le contrôle instantanément

## Comment marche le verrouillage

- ENTRER EN FOCUS → écran de blocage plein écran + service de surveillance en tâche de fond
- Toute tentative d'ouvrir une autre app (accueil, récentes, notifications) → retour immédiat sur l'écran de blocage, **fuite comptée et affichée**
- Sortie uniquement en recopiant : « Je certifie sur l'honneur avoir accompli cette tâche. »
- La certification coche automatiquement le bloc dans la journée
- « VOIR POURQUOI » → textes / images / vidéos de motivation (onglet POURQUOI pour les remplir, hors focus)

## Recompiler

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd "1-PROJETS/LifeOS/android"
gradle :app:assembleDebug
```

APK produit : `app/build/outputs/apk/debug/app-debug.apk`

## Structure du code

| Fichier | Rôle |
|---|---|
| `Schedule.kt` | Emploi du temps été 2026, objectifs hebdo, projets (miroir du vault) |
| `Store.kt` | État persistant (coches, streak, focus, quotas, POURQUOI) |
| `FocusService.kt` | Service de surveillance — détecte et rattrape les fuites |
| `BlockerActivity.kt` | Écran de verrouillage + certification sur l'honneur |
| `MainActivity.kt` | 5 onglets : NOW / SEMAINE / PROJETS / POURQUOI / STREAK |
| `Ui.kt` | Design system noir & blanc + rendu des items POURQUOI |
