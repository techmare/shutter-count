# Page « Nombre de déclenchements » pour midoxshop.ma

Un seul fichier : `index.html`. Tout est dedans (le lecteur de photos, la lecture USB et le logo),
il fonctionne donc aussi ouvert directement depuis le téléphone (app Fichiers).

## Mise en ligne (site Next.js sur Vercel)

1. Copier `dist/index.html` et `apercu.png` dans `public/declenchements/` du projet.
   (`apercu.png` est l'image affichée quand le lien est partagé sur WhatsApp ou Facebook.)
2. Déployer. La page est à **https://www.midoxshop.ma/declenchements/**
3. Ajouter le lien « Déclenchements » dans le menu ou le pied de page du site et dans `sitemap.xml`,
   puis demander l'indexation de la page dans Google Search Console.

## USB

Le bouton « Brancher l'appareil en USB » n'apparaît que sur Android (Chrome), où rien n'est à régler.
Au magasin, sur Mac : ajouter `?usb=1` à l'adresse. Seules des commandes de lecture sont envoyées.

Rien n'est envoyé à un serveur : la photo est lue dans le navigateur du visiteur.

(Pour les développeurs : la page est générée par `build.py` à partir de `index.html`, `../reader.js` et `../ptp-usb.js`.)
