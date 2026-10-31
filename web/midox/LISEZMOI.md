# Page « Nombre de déclenchements » pour midoxshop.ma

Deux fichiers, rien à installer :

- `index.html`: la page (thème midoxshop.ma : Kanit, texte ardoise #334155, or #d4a74b, cuivre #955228, anthracite #1f1f1f)
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
qui le permettent. Seules des commandes de lecture sont envoyées.
Le bouton n'apparaît que sur Android (Chrome), où rien n'est à régler. Pour l'activer ailleurs (au magasin) : ajouter `?usb=1` à l'adresse.
La page doit être servie en HTTPS (c'est le cas sur midoxshop.ma).
