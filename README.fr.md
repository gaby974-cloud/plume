# Plume · Handwriting + Claude — guide du développeur (français)

Carnet manuscrit pour liseuse Boox (Note Air C, Android 11+), avec un bouton qui envoie la page à Claude et pose sa réponse sur la page en encre bleue.

- Écriture basse latence via le SDK stylet Onyx (`TouchHelper`, raw drawing) ; mode dégradé automatique (événements tactiles Android) sur tout autre appareil, émulateur compris.
- Hors ligne d'abord : tout fonctionne sans réseau, sauf l'appel à Claude.
- Interface en français et en anglais, pensée pour l'encre électronique : noir et blanc, bleu `#1B4EA3` pour Claude, aucune animation. Les encres (stylo, surligneur) sont en couleur pour l'écran Kaleido.

## Compiler

Prérequis : JDK 17 et SDK Android (plateforme 35). Créez `local.properties` avec `sdk.dir=…` si Android Studio ne l'a pas fait.

```bash
./gradlew assembleDebug
```

L'APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

Version publique signée : `./gradlew assembleRelease`, avec dans `local.properties` les lignes `release.storeFile`, `release.storePassword`, `release.keyAlias` et `release.keyPassword` (clé hors du dépôt, à sauvegarder : sans elle, plus aucune mise à jour ne s'installe par-dessus). Sans ces lignes, l'APK de version n'est pas signé.

Tests unitaires (encodage des traits, taille d'une page chargée, détection des ratures) :

```bash
./gradlew testDebugUnitTest
```

## Installer sur la Boox

1. Sur la Boox : Réglages → À propos de l'appareil, toucher 7 fois le numéro de build pour activer le mode développeur, puis activer le **débogage USB** dans les options pour les développeurs.
2. Brancher la Boox au PC et accepter l'autorisation de débogage affichée sur la liseuse.
3. Installer l'APK :

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Dans les réglages Boox de l'application (appui long sur l'icône → optimisation de l'application), choisir le mode de rafraîchissement rapide si l'interface paraît lente. L'écriture elle-même ne dépend pas de ce réglage : elle passe par le SDK stylet.
5. Au premier lancement, ouvrir **Réglages** et coller la clé API Anthropic, à créer sur [console.anthropic.com](https://console.anthropic.com).

## Utilisation

Un mode d'emploi complet est intégré : **Réglages → Mode d'emploi** (rubriques à déplier).

L'application existe en français et en anglais : **Réglages → Langue** (Automatique = la langue de la liseuse). Les consignes envoyées à Claude sont dans `assets/modes.json` (français) et `assets/modes-en.json` (anglais), avec les mêmes identifiants de modes ; dans le code, chaque texte d'interface passe par `tr("français", "english")` (`Lang.kt`).

### Carnets : pages ou page infinie

À la création, on choisit :
- **Pages** : des pages successives au format 3:4. En haut à droite de la page : ‹, le compteur n / N, ›, et ✕ pour quitter le carnet. Sur la dernière page, › (ou un balayage vers la gauche au doigt) crée une nouvelle page.
- **Page infinie** : une seule page qui s'allonge vers le bas à mesure qu'on écrit ; il reste toujours de la place sous l'écriture.

Dans les deux cas, **Claude ne change jamais de page** : une réponse posée se place plus bas, la page s'allonge si besoin et l'écran défile jusqu'à elle. Quand une page est plus haute que l'écran, Claude lit la partie visible (ou la zone si elle est tracée).

### Barre d'outils

Une colonne de grandes icônes légendées en gras sur le côté (à gauche par défaut, à droite dans les Réglages), la page en plein écran. « Masquer la barre d'outils » (dans Menu) la replie ; le bouton « Outils » dans le coin la fait revenir.

| Bouton | Rôle |
|---|---|
| 3 stylos favoris | Un appui sélectionne. Un nouvel appui (ou un appui long) règle le favori : stylo ou surligneur, couleur, épaisseur. Par défaut : noir, rouge, surligneur jaune. |
| Gomme | Efface le trait entier touché, ou un bloc de Claude. Le bout gomme ou le bouton latéral du stylet gomment aussi. |
| Zone | Tracer un cadre : Claude ne lira que ce cadre. Un appui court retire la zone. |
| Lasso | Entourer librement des traits ou des blocs de Claude pour les sélectionner (voir ci-dessous). |
| ↶ ↷ | Annuler / rétablir, sans limite, sur la page courante. |
| ˄ ˅ Défiler | Défilement d'un écran (page infinie, ou page allongée par une réponse). |
| Claude | Envoie la page (ou la zone) et ouvre le panneau de réponse. |
| Mode ▾ | Auto, Transcrire, Résumer, Continuer, Structurer, Schéma, Tableau à remplir, Calendrier, Vérifier. |
| Menu | Nom du carnet ✎ (un appui pour le renommer), historique des 8 dernières réponses, export PNG / PDF, suppression de page, masquer la barre, réglages. |

**Stylo couleurs** : noir, rouge, bleu, vert, orange, violet ; fin, moyen, épais, très épais ; le trait varie avec la pression. **Surligneur** : jaune, vert, rose, bleu, orange ; fin, moyen, large ; il passe sous l'encre.

### Lasso

Avec l'outil Lasso, tracer une boucle autour de ce qu'on veut prendre : un trait est sélectionné si au moins la moitié de ses points sont dans la boucle, un bloc de Claude si son centre y est. Un cadre en pointillés entoure la sélection et une barre d'actions apparaît en haut de la page :

- **Glisser** la sélection (stylet ou doigt dans le cadre) pour la déplacer ;
- **Couleur ▾** : une couleur d'encre recolore les traits au stylo, une couleur de surligneur recolore le surlignage, sans toucher à l'autre ;
- **Réduire / Agrandir** autour du centre de la sélection ;
- **Dupliquer**, **Supprimer** ;
- **Claude** : Claude ne lit que les éléments entourés, et sa réponse se pose juste dessous ;
- **✕** ou un simple appui hors du cadre retire la sélection.

Chaque action s'annule en une seule étape.

### Le stylet écrit, les doigts commandent

Avec « Stylet seul » (par défaut sur Boox), les doigts n'écrivent jamais et servent aux gestes :

| Geste au doigt | Effet |
|---|---|
| Toucher à deux doigts | Annuler |
| Toucher à trois doigts | Rétablir |
| Balayer vers la gauche / la droite | Page suivante (ou nouvelle page) / précédente |
| Glisser vers le haut / le bas | Faire défiler une page plus haute que l'écran |
| Appui court | Retirer la zone |
| Appui long sur une réponse de Claude (texte bleu) puis glisser | Déplacer la réponse |

Un trait rapide au stylet ne déclenche jamais ces gestes.

**Raturer pour effacer** : au stylo, faire des allers-retours serrés (zigzag ou gribouillis) par-dessus un mot l'efface, et la rature disparaît avec lui. « Annuler » le fait revenir. Un zigzag tracé sur une zone vide reste un trait normal (hachures), et un simple trait sous un mot (soulignement) n'efface rien.

La page est un fond blanc uni, sans grille ni points.

Le panneau de réponse (40 % de la hauteur, ou tout l'écran avec « Agrandir ») affiche la réponse au fil de l'eau. Une fois finie, elle se lit page par page avec ‹ Préc. / Suiv. › (pas de défilement continu, qui laisse des traînées sur l'encre). Il propose Arrêter, Relancer, Copier, Fermer, une question de suite, et **Poser sur la page** : la réponse, en bleu, se place sous la zone, sinon sous le contenu le plus bas, en allongeant la page si besoin.

En mode Auto, écrire par exemple `→ résume`, `-> traduis en anglais` ou `@claude …` en bas de page : Claude exécute la consigne sans la commenter. **Sans consigne identifiable, Auto transcrit l'écriture manuscrite en texte**, en gardant sa structure et sans recopier ce que Claude a déjà posé.

### Schémas, tableaux, calendriers

Quand la demande appelle un visuel (`→ fais-moi un calendrier de novembre`, `→ tableau pour suivre mes dépenses`, `→ schéma de ce plan`, ou les modes **Schéma**, **Tableau à remplir** et **Calendrier**), Claude répond par un dessin vectoriel (SVG) au lieu de texte. Le panneau en montre l'aperçu ; « Poser sur la page » le pose en bleu sous la zone ou sous le contenu. On écrit dans ses cases au stylet, on le déplace, l'agrandit ou le supprime au lasso, on l'efface à la gomme. Claude connaît la date du jour, pour des calendriers justes.

Les règles de dessin (encre bleue, cases assez grandes pour écrire, texte lisible sur encre électronique) sont dans le prompt de [modes.json](app/src/main/assets/modes.json). Le rendu utilise la bibliothèque AndroidSVG, qui n'exécute aucun script et ne charge rien d'extérieur.

### Ajouter un mode

Les modes et le prompt de base sont dans [app/src/main/assets/modes.json](app/src/main/assets/modes.json). Ajouter un objet `{ "id", "label", "task" }` au tableau `modes`, recompiler : le mode apparaît dans le menu.

**Modes personnels, non publiés :** les placer dans `app/perso/assets/modes-perso.json` (et `modes-perso-en.json`), au format `{ "modes": [ … ] }`. Ce dossier est ignoré par git. Ils ne sont inclus que dans une version compilée avec `-Pperso` (par exemple `./gradlew assembleDebug -Pperso`) et s'ajoutent à la fin du menu ; une compilation normale n'en contient jamais.

## Retrouver ses carnets sur l'ordinateur : Google Drive

Chaque carnet est copié en PDF quand on le quitte (ou quand l'application passe en arrière-plan) :
- sur la liseuse, dans `Documents/Plume` ;
- **sur Google Drive, dans un dossier « Plume »**, une fois Drive connecté. On y accède de partout (ordinateur, téléphone), sans être sur le même Wi-Fi.

Les fichiers s'appellent `Titre du carnet (n°).pdf` et sont remplacés à chaque mise à jour (renommer le carnet renomme la copie, le supprimer met la copie Drive à la corbeille). Le PDF est vectoriel et son texte (réponses de Claude, schémas) reste sélectionnable.

Plume n'a accès qu'aux fichiers qu'il a créés dans le Drive (portée `drive.file`). La connexion passe par le navigateur de la liseuse et fonctionne sans les services Google Play ; l'accès est chiffré sur la liseuse et révocable à tout moment (Réglages → Déconnecter, ou myaccount.google.com → Sécurité).

La rubrique **Notes** de push.boox.com n'affiche que les carnets de l'application Boox Notes, et BooxDrop ne montre que son propre dossier : d'où le choix de Drive.

### Configuration Google (une seule fois)

1. Sur [console.cloud.google.com](https://console.cloud.google.com), créer un projet `Plume` et le sélectionner.
2. **API et services → Bibliothèque** : activer **Google Drive API**.
3. **Écran de consentement OAuth** (Google Auth Platform) : nom `Plume`, audience **Externe**, ton adresse en contact ; puis dans **Audience**, **Publier l'application** (sinon la connexion expire tous les 7 jours).
4. **Clients** (ou Identifiants → ID client OAuth) : type **Application de bureau**, nom `Plume Boox`. Noter l'**ID client** et le **code secret**.
5. Ajouter à `local.properties` :

   ```
   drive.clientId=…apps.googleusercontent.com
   drive.clientSecret=…
   ```

6. Recompiler (`./gradlew assembleDebug`), installer, puis dans Plume : **Réglages → Connecter Google Drive**, se connecter dans la page Google, revenir dans Plume, et **Envoyer tous les carnets maintenant**.

Google peut afficher « Google n'a pas validé cette application » : c'est normal tant que la marque n'est pas validée par Google ; choisir **Paramètres avancés → Accéder à Plume**.

## Coût et clé API

- L'API Anthropic est facturée à l'usage, **en dehors de tout abonnement Claude**. Les Réglages affichent les tokens consommés sur le mois en cours (entrée / sortie), d'après le champ `usage` des réponses.
- Modèle par défaut : `claude-sonnet-5-5` ; au choix `claude-opus-5-5` (plus fin, plus cher) et `claude-haiku-5-5` (rapide, économique).
- La clé est stockée chiffrée (`EncryptedSharedPreferences`, clé maîtresse dans l'Android Keystore), n'est envoyée qu'à `api.anthropic.com` dans l'en-tête `x-api-key`, et n'est jamais écrite dans les logs. Vérification :

  ```bash
  adb logcat -d | grep -c "sk-ant"
  ```

  doit afficher `0`.

## Architecture

```
app/src/main/java/com/alphateam/carnetencre/
├── CarnetApp.kt           Application : base Room, réglages, modes, client Claude
├── MainActivity.kt        Navigation Accueil / Carnet / Réglages
├── page/
│   ├── PageView.kt        SurfaceView de la page : rendu, gomme, zone, déplacement des blocs, mode dégradé
│   └── OnyxPen.kt         Enveloppe du SDK Onyx (TouchHelper, EpdController, rafraîchissement GC)
├── model/                 Éléments (traits, blocs texte), annuler/rétablir, encodage compact, effacement par rature
├── render/                Rendu des traits (pression, couleur, surligneur sous l'encre) et des réponses de Claude (texte bleu, trait à gauche)
├── claude/                Appel streaming SSE à l'API Messages, modes
├── data/                  Room (Notebook, Page, Element, Answer), réglages chiffrés
├── export/                PNG, PDF, partage et Téléchargements
└── ui/                    Écrans Compose et composants e-ink
```

- Page logique de 1404 unités de large et d'au moins 1872 de haut (plus si elle s'allonge), mise à l'échelle de l'écran. La base Room est en version 2 (mode des carnets, hauteur des pages) avec une migration depuis la v1 qui garde les carnets existants. Les exports PDF découpent une page allongée en pages 3:4.
- Traits encodés en delta (demi-unités, pression 0–9, varints) : environ 3 octets par point ; une page de 48 000 points tient sous 200 Ko (test unitaire).
- Sauvegarde automatique 1,5 s après chaque modification et à la mise en arrière-plan.
- Rafraîchissement complet (mode GC) après une gomme, un annuler/rétablir, un déplacement ou une réponse posée.

## Points à vérifier sur la Boox

Testé sur émulateur (mode dégradé) et sur Boox Note Air C. Sur un autre modèle, valider :

- la latence du trait et l'absence de points perdus en écriture rapide ;
- le rendu du trait enregistré face à celui tracé par le SDK (style « fountain » pour le stylo, « marker » pour le surligneur) et le rendu des couleurs sur l'écran Kaleido ;
- la sensibilité de la rature avec une vraie écriture (seuils dans `model/ScribbleEraser.kt`) ;
- le rafraîchissement GC après gomme et annuler (`EpdController.refreshScreen`) ;
- le bout gomme / bouton latéral du stylet.

## Licences des polices

Atkinson Hyperlegible (Braille Institute) et Literata (TypeTogether) sont distribuées sous SIL Open Font License 1.1 (textes dans `licenses/`). Le code de Plume est sous licence MIT (`LICENSE`).
