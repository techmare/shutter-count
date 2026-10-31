# Page « Nombre de déclenchements » pour midoxshop.ma

Deux fichiers, rien à installer :

- `index.html`: la page (thème Midox : bleu nuit #1d2742, or #c59a3c, Kanit + Figtree)
- `reader.js`: le lecteur de photos (même code que l'app Shutter Count)

## Mise en ligne (site Next.js sur Vercel)

1. Copier le dossier dans `public/declenchements/` du projet.
2. Déployer. La page est à **https://www.midoxshop.ma/declenchements/**
3. Ajouter le lien « Déclenchements » dans le menu du site et dans `sitemap.xml`.

Le logo est chargé depuis `/assets/images/logo.png` (celui du site). Si ce chemin change, modifier
la balise `<img>` du header. Les numéros WhatsApp sont ceux du site (+212 6 21 31 98 82).

Rien n'est envoyé à un serveur : la photo est lue dans le navigateur du visiteur.

## USB (Chrome et Edge)

`ptp-usb.js` ajoute « Brancher l'appareil en USB » : le bouton n'apparaît que dans les navigateurs
qui le permettent (Chrome / Edge sur Android, Mac, PC). Seules des commandes de lecture sont envoyées.
La page doit être servie en HTTPS (c'est le cas sur midoxshop.ma).
