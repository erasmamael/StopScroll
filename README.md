# StopScroll

App Android personnelle qui empêche le défilement infini des Reels Instagram :

- l'onglet **Reels** est inaccessible ;
- un Reel ouvert (message, fil, Explorer, profil) reste visible, mais **impossible de passer au suivant** ;
- tout le reste d'Instagram fonctionne normalement.

Fonctionne grâce à un **service d'accessibilité** limité à Instagram. Aucune permission Internet, aucune donnée collectée.

---

## Installer / mettre à jour

Téléphone branché en USB (débogage USB activé), depuis le dossier du projet :

```bash
# Version finale (usage quotidien)
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# Version debug (voiles colorés + journal + export de l'arbre)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Les deux versions sont signées avec la clé debug d'Android Studio (`~/.android/debug.keystore`) : on passe de l'une à l'autre sans perdre ni les réglages ni l'activation du service.
⚠️ Sur un autre PC (autre clé), il faut d'abord désinstaller l'app.

Après une réinstallation, vérifier dans l'app que l'accueil affiche **« Blocage actif ✅ »**.

---

## Quand Instagram se met à jour et que le blocage ne marche plus

Instagram renomme parfois ses éléments internes. Symptôme : l'onglet Reels s'ouvre de nouveau, ou on peut repasser d'un Reel à l'autre.

1. **Dépannage immédiat** : Réglages → méthode **« Fermer le lecteur »** (plus robuste).
2. Installer la **version debug**.
3. Réglages → **Debug** → « Exporter l'arbre dans 5 s », puis ouvrir Instagram sur l'écran qui pose problème (onglet Reels, ou un Reel ouvert).
4. Revenir dans StopScroll → « Partager le dernier export ».
5. Dans le fichier, chercher les nouveaux identifiants (`id=…`) et les reporter dans **un seul fichier** :
   [`app/src/main/java/fr/brion/stopscroll/service/InstagramSelectors.kt`](app/src/main/java/fr/brion/stopscroll/service/InstagramSelectors.kt)

   | Repère | Identifiant actuel | Où le chercher dans l'export |
   |---|---|---|
   | Onglet Reels | `clips_tab` | barre du bas, `desc="Reels"`, marqué `[sélectionné]` quand actif |
   | Onglet Accueil | `feed_tab` | barre du bas, `desc="Home"` |
   | Lecteur de Reels | `clips_viewer_view_pager` | grand `ViewPager` `[défilable]` |
   | Boutons de droite | `clips_ufi_component` | contient `like_button`, `comment_button`… |
   | Zone auteur / légende | `clips_media_info_component` | contient `clips_author_username` |
   | Barre de lecture | `scrubber`, `clips_expanded_touch_view` | en bas du lecteur |
   | Commentaires / partage | `bottom_sheet_container` | apparaît quand le panneau est ouvert |

   Ajouter le nouvel identifiant à la liste (sans retirer l'ancien), recompiler, réinstaller.
6. Penser à **supprimer les exports** ensuite (ils contiennent les textes affichés à l'écran).

---

## Limites connues

- **Appui long** sur un Reel (pause maintenue, vitesse ×2) : désactivé. Android annule tout geste simulé dès que le vrai doigt bouge, il est donc impossible de le retransmettre.
- **Latence** : chaque appui est rejoué à Instagram après coup (~80 ms sur les boutons, ~0,4 s pour la pause, à cause de l'attente d'un éventuel double-tap).
- Un swipe démarré **exactement** à l'endroit d'un tap, ~0,4 s après, peut passer. Le filet de sécurité ferme alors le Reel.
- Option Instagram **« ⋯ → Lecture → Défilement automatique »** : à laisser **désactivée**, sinon le Reel se ferme à la fin de chaque vidéo.

---

## Architecture (pour s'y retrouver)

| Fichier | Rôle |
|---|---|
| `service/StopScrollService.kt` | Le service : reçoit les événements, analyse l'écran (fil séparé), applique les règles |
| `service/InstagramSelectors.kt` | **Tous** les identifiants Instagram (seul fichier à modifier après une mise à jour) |
| `service/ScreenDetector.kt` | Reconnaît l'onglet Reels, le lecteur, les panneaux |
| `service/ReelsTabBlocker.kt` | Règle 1 : bouclier invisible sur l'onglet Reels |
| `service/SwipeBlockerOverlay.kt` | Règle 3 : overlay qui absorbe les swipes et rejoue taps / double-taps |
| `service/ScrollSafetyNet.kt` | Filet : changement de Reel détecté → touche Retour |
| `policy/BlockingPolicy.kt` | Point central « faut-il bloquer maintenant ? » (futures plages horaires) |
| `data/SettingsRepository.kt` | Réglages (DataStore, local) |
| `ui/` | Écrans Compose |
| `debug/TreeDumper.kt` | Export de l'arbre d'accessibilité |
