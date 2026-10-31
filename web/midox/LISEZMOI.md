# Page « Nombre de déclenchements » pour midoxshop.ma

Un seul fichier : `index.html`. Tout est dedans (le lecteur de photos, la lecture USB et le logo),
il fonctionne donc aussi ouvert directement depuis le téléphone (app Fichiers).

## Mise en ligne (site Next.js sur Vercel)

1. Copier `index.html` dans `public/declenchements/` du projet.
2. Déployer. La page est à **https://www.midoxshop.ma/declenchements/**
3. Ajouter le lien « Déclenchements » dans le menu du site et dans `sitemap.xml`.

## USB

Le bouton « Brancher l'appareil en USB » n'apparaît que sur Android (Chrome), où rien n'est à régler.
Au magasin, sur Mac : ajouter `?usb=1` à l'adresse. Seules des commandes de lecture sont envoyées.

Rien n'est envoyé à un serveur : la photo est lue dans le navigateur du visiteur.

(Pour les développeurs : la page est générée par `build.py` à partir de `index.html`, `../reader.js` et `../ptp-usb.js`.)
