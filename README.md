# iTV

Lecteur IPTV pour Android TV / Google TV (testé dans l'esprit de la **Freebox Pop**).
L'application ne contient aucune chaîne : elle lit uniquement les flux de **ton** abonnement
(identifiants Xtream Codes ou playlist M3U).

## Fonctions de cette première version

- Connexion par Xtream Codes (serveur, utilisateur, mot de passe) ou par URL de playlist M3U
- Design minimaliste inspiré d'Apple TV : fond noir, rangées horizontales par catégorie,
  vignettes arrondies qui s'agrandissent au focus, onglets en pastilles
- Accueil en rangées par catégorie ; un onglet de catégorie ou une recherche affiche une grille
- Logos des chaînes, recherche et favoris
- Bandeau « Reprendre » avec la dernière chaîne regardée, rangées « Favoris » et « Récents », pastille EN DIRECT
- Écran de démarrage avec le logo en fondu
- Lecture plein écran (ExoPlayer, décodage matériel, HLS), zapping rapide, bandeau d'infos en fondu
- Cache local de la liste : l'écran d'accueil s'affiche tout de suite, la mise à jour se fait en arrière-plan

### Films et séries (comptes Xtream Codes uniquement)

- Menu vertical à gauche : **Chaînes · Films · Séries**, recherche et réglages
- Rangées d'affiches par catégorie, grille par catégorie, recherche
- Page de détail : affiche, résumé, année, durée, note, genre
- Séries : choix de la saison, vignettes d'épisodes avec **titre, durée et résumé** de l'épisode sélectionné, passage automatique à l'épisode suivant
- Lecteur : touche **Bas** pour ouvrir le panneau d'options à droite — épisode suivant, piste audio, sous-titres, qualité vidéo (flux à plusieurs débits) et vitesse de lecture
- Lecteur : barre fine qui grossit pendant les sauts, avec l'heure de fin ; carte « Épisode suivant » pendant le générique (lecture automatique après 8 s)
- Séries : gros bouton « Reprendre S1 · E3 » (ou « Lecture »), menu déroulant des saisons, coche « vu » sur les épisodes terminés
- Films : rangées « Ajouts récents » et « Les mieux notés », note ★ sur les affiches
- Reprise de lecture : barre de progression sur les affiches et rangée « Reprendre »
- Les catalogues sont mis en cache ; le premier chargement peut être long si le serveur est très fourni

Avec une playlist M3U, les films et séries n'apparaissent pas comme sections : ils sont listés comme
des chaînes, selon les groupes de la playlist.

### Réglages et catégories

Le bouton **Réglages** (en haut à droite) permet de choisir ce qui s'affiche :

- Catégories des chaînes, des films et des séries : chaque ligne bascule **Affichée / Masquée**
  (le nombre d'éléments de la catégorie est indiqué)
- Un champ de filtre et les boutons « Masquer les résultats » / « Afficher les résultats »
  pour traiter d'un coup toutes les catégories qui contiennent un mot
- « Tout masquer » puis réactiver seulement celles qui t'intéressent
- Les catégories masquées disparaissent de l'accueil, des onglets, des grilles et de la recherche
- Le choix est mémorisé sur l'appareil, séparément pour les chaînes, les films et les séries
- Actualiser les catalogues, changer de compte, se déconnecter

Pas encore : guide des programmes (EPG) et enregistrement.

## Sécurité

- Identifiants chiffrés sur l'appareil (Android Keystore, AES-GCM) ; rien n'est enregistré si le chiffrement échoue
- Aucun identifiant dans le cache, l'historique de reprise ni les listes de chaînes : les adresses de lecture sont reconstruites en mémoire au moment de lire
- Une playlist M3U (dont l'adresse peut contenir tes identifiants) n'est pas mise en cache : elle est rechargée à chaque démarrage
- Sauvegarde Android désactivée, une seule permission (Internet), une seule activité exposée
- Pas de retour automatique de https vers http lors des redirections
- Le trafic en http est autorisé, car beaucoup de serveurs IPTV n'ont pas de https ; l'écran de connexion t'avertit
- APK compilé en version finale (non débogable), signé pendant la compilation, sans action GitHub tierce pour la publication

**Mises à jour** : sans configuration, chaque compilation utilise une clé de signature temporaire.
Android refuse alors d'installer la nouvelle version par-dessus l'ancienne : désinstalle d'abord iTV
(tu devras te reconnecter). Pour des mises à jour sur place, ajoute dans le dépôt (Settings > Secrets and
variables > Actions) les secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD`.

## Commandes à la télécommande

| Action | Touche |
|---|---|
| Naviguer | Flèches |
| Regarder une chaîne | OK |
| Ajouter / retirer un favori | OK long (sur une chaîne) |
| Chaîne suivante / précédente | Haut / Bas (ou CH+ / CH-) |
| Afficher le nom de la chaîne | OK pendant la lecture |
| Revenir à la liste | Retour |
| Film ou épisode : pause / lecture | OK |
| Film ou épisode : avancer / reculer de 10 s | Droite / Gauche |
| Film ou épisode : options (audio, sous-titres, qualité, suivant) | Bas |

## Obtenir l'APK sans Android Studio (GitHub)

1. Crée un compte gratuit sur github.com, puis un **nouveau dépôt public** (bouton « New »).
2. Dans le dépôt : « uploading an existing file », puis glisse-dépose **tout le contenu** de ce dossier
   (y compris le dossier caché `.github`). Valide avec « Commit changes ».
   - Si `.github` n'a pas été envoyé : « Add file » > « Create new file », tape le nom
     `.github/workflows/build.yml` et colle le contenu du fichier du même nom.
3. Ouvre l'onglet **Actions** : la compilation démarre toute seule (3 à 6 minutes).
4. Quand elle est verte, l'APK est disponible :
   - dans l'onglet **Releases** (à droite du dépôt), fichier `iTV.apk`, avec un lien direct ;
   - ou dans le résumé de l'exécution, section « Artifacts ».

Si la compilation échoue, ouvre l'étape rouge, copie le message d'erreur et envoie-le moi.

## Installer sur la Freebox Pop

1. Sur le Player : installe **Downloader** (by AFTVnews) depuis le Play Store.
2. Paramètres > Applications > Sécurité et restrictions > **Sources inconnues** : autorise Downloader.
3. Ouvre Downloader et saisis le lien direct de l'APK :
   `https://github.com/TON-COMPTE/TON-DEPOT/releases/download/derniere-version/iTV.apk`
4. Télécharge, puis « Installer ».

Alternative : copie `iTV.apk` sur une clé USB et ouvre-la avec un gestionnaire de fichiers sur le Player.

## Structure du code

- `data/` : modèles, client Xtream, lecteur M3U, cache, favoris et identifiants (DataStore)
- `AppViewModel.kt` : état de l'application
- `ui/` : écrans Compose (connexion, liste des chaînes, lecteur)

## Police

L'app utilise **Inter** (licence SIL OFL, voir `FONT-LICENSES.txt`), la police libre la plus proche de la police d'Apple ; le logo utilise **Sora Bold** (également SIL OFL).

## Légalité

L'application est un simple lecteur. Utilise-la uniquement avec des flux que tu as le droit de regarder.
