# n7Folder Player

Clone de l'UX n7player, mais 100 % basé sur les dossiers réels (SD, mémoire interne, USB OTG,
dossier réseau exposé par une app de stockage) — jamais les tags ID3.

## État du projet

**Itérations 1 et 2 livrées.**

### Itération 1 — Analyse
- `data/PathNormalizer.kt` : ParsePath v6+ (Saez, Black Sabbath/années, CD1/CD2, feat/avec/&,
  dossiers génériques, tags qualité, identité Angèle=Angele=ANGELE).
- `data/FileScanner.kt` : parcours SAF itératif (pas de récursion), lots de 100 à 500 pistes,
  pochettes locales (cover/folder/front…), anti-freeze jusqu'à 50 000 fichiers.
- `data/{LibraryModels,LibraryIndex,SourceStore}.kt` : modèles, fusion progressive en
  artistes/albums, persistance des dossiers sources (SAF + relink).
- `.github/workflows/build-apk.yml` : build CI (tests, APK debug + release).

### Itération 2 — Lecture et interface finale
- `service/MusicService.kt` + `playback/{PlaybackController,MediaItemFactory,EqualizerEngine}.kt` :
  Media3 ExoPlayer + MediaLibrarySession (notification, écran verrouillé, Bluetooth AVRCP, lecture
  en arrière-plan), égaliseur 10 bandes (DynamicsProcessing sur Android 9+, repli Equalizer sinon),
  file d'attente construite par tranches (jamais des dizaines de milliers d'éléments d'un coup).
- `data/CoverArtManager.kt` : pochettes intégrées aux fichiers (2ᵉ temps), extraites à la demande,
  réduites à 512 px, cache disque plafonné à 64 Mo.
- `ui/canvas/{ArtistWordLayout,N7ZoomableSurface}.kt` : le nuage d'artistes zoomable — pincer pour
  zoomer/dézoomer, glisser avec inertie, zoom sémantique (zoomer fort sur un artiste puis relâcher
  "entre" dedans). Mise en page calculée hors du thread principal, dessin uniquement des lignes
  visibles.
- `ui/library/*` : grille de pochettes (artiste), liste de pistes (album), explorateur arborescent
  avec fil d'Ariane, barre alphabétique A-Z avec compteurs.
- `ui/player/*` : mini-lecteur persistant, écran "Lecture en cours", file d'attente avec
  glisser-déposer, égaliseur graphique.
- `ui/N7App.kt` : navigation racine (nuage → artiste → album, explorateur, sources, panneaux du
  lecteur), retour matériel géré, pincer pour revenir en arrière.

## Compiler

```
./gradlew assembleDebug      # ou via le workflow GitHub Actions (déclenché sur push/PR)
./gradlew testDebugUnitTest  # tests JVM purs : PathNormalizer, Alphabet, ArtistWordLayout
```

## Limites de vérification connues

Le code a été écrit et relu dans un environnement **sans réseau et sans compilateur Kotlin**.
Vérifications réellement effectuées :
- Les algorithmes purs (`PathNormalizer`, `ArtistWordLayout`) ont été **portés en Java, compilés et
  exécutés** avec des dizaines de milliers d'assertions (cas réels + tirages aléatoires comparés à
  une recherche en force brute). C'est la seule vérification équivalente à une exécution réelle.
- Le reste du code (Compose, Media3, tout ce qui touche à l'framework Android) a été relu
  manuellement et passé dans deux vérificateurs Python maison : équilibre des accolades, imports
  manquants pour ~60 extensions Compose/coroutines courantes, et cohérence de chaque appel de
  fonction/constructeur du projet avec sa signature (arguments nommés, obligatoires, arité). Zéro
  alerte au dernier passage.
- **Aucune compilation Kotlin/Gradle réelle n'a pu avoir lieu.** Le premier passage du workflow
  GitHub Actions est donc la vraie vérification finale. En cas d'échec, coller le journal d'erreur
  suffit pour une correction ciblée.

### Itération 2.1 — Pochettes en ligne et clarté des dossiers
- `data/RemoteCoverProvider.kt` : dernier maillon de la chaîne de pochettes — interrogation de
  TheAudioDB (clé de test publique, sans inscription), puis Last.fm si une clé est renseignée dans
  `LASTFM_API_KEY` (vide par défaut). Réglable depuis l'écran Sources ("Pochettes manquantes en
  ligne"), désactivable pour rester hors ligne.
- `data/CoverArtManager.kt` : chaîne unifiée locale → tags embarqués → en ligne, sous une seule clé
  de cache (la première source qui répond gagne ; échec de la chaîne entière = jamais retentée).
- `ui/library/ArtistScreen.kt` : le nom de l'album est maintenant toujours visible, hauteur
  réservée, plus de contraste — pour ne jamais perdre de vue de quel dossier il s'agit, même sans
  pochette.
- `ui/canvas/N7ZoomableSurface.kt` : zoom sémantique progressif — à partir d'un certain niveau de
  zoom, une vignette apparaît en fondu à côté du nom de chaque artiste (pochette locale déjà connue
  uniquement ; jamais de recherche en ligne pour tout le nuage à la fois).

### Itération 2.2 — Bibliothèque figée entre les lancements
- `data/LibraryCache.kt` (nouveau) : la bibliothèque déjà analysée (pistes + pochettes locales) est
  écrite sur le disque (JSON compressé, stockage privé de l'app) dès qu'une analyse se termine.
- `ui/scan/ScanViewModel.kt` : au démarrage, si ce cache existe, il est restitué **instantanément**,
  sans repasser par le sélecteur de stockage (SAF) — même après un redémarrage du téléphone. Trois
  actions, et trois seulement, relancent une vraie analyse : le bouton "Réanalyser", l'ajout d'un
  nouveau dossier, et un réglage qui change l'interprétation des fichiers ("Ce dossier est un
  artiste", ou "Relier" après reconnexion d'un stockage). Retirer un dossier ne relance rien : la
  bibliothèque déjà en mémoire est simplement filtrée, puis le cache mis à jour.

## Reste à faire (hors périmètre des itérations 1-2)

- Réglages fins de l'UX zoomable (tailles, couleurs, animations) selon le ressenti sur appareil réel.
- Écran de recherche.
- Réglages avancés (formats de sortie, gapless, remplacement silencieux).
- La révélation progressive des vignettes sur le nuage d'artistes n'utilise que les pochettes
  locales déjà connues (pas les tags embarqués ni le résultat des recherches en ligne) : interroger
  ces sources pour tous les artistes visibles en même temps serait disproportionné. Un
  enrichissement (par exemple mémoriser les pochettes déjà résolues ailleurs dans l'app) est possible
  plus tard si souhaité.
- TheAudioDB : la clé "2" est la clé de test publique documentée par l'éditeur, pensée pour un usage
  personnel à faible volume — largement suffisante pour une bibliothèque perso, mais à remplacer par
  une clé propre en cas d'usage intensif ou de partage à plusieurs personnes.
- Le cache de bibliothèque est une seule "photo" complète de la bibliothèque (pas un historique ni
  une base incrémentale) : chaque analyse réussie le remplace entièrement. Si un fichier est
  déplacé/supprimé directement sur le stockage sans passer par l'app, la bibliothèque figée ne le
  saura pas tant qu'une nouvelle analyse n'aura pas été demandée.
