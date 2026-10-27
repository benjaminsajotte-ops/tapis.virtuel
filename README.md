# Tapis virtuel

App Android qui fait passer le téléphone pour un tapis de course connecté (Bluetooth FTMS),
pour courir sur un tapis classique avec MyWhoosh (ou Zwift). Vitesse et inclinaison se règlent
à la main dans l'app. Charte graphique : Tetra Coach.

## Compiler avec Codemagic
1. Mets tout le contenu de ce dossier dans un dépôt GitHub (avec `gradle/`, `gradlew` et `codemagic.yaml`).
2. Dans Codemagic : ajoute le dépôt, configuration `codemagic.yaml`, lance « Tapis virtuel Android ».
3. Télécharge `app-debug.apk` sur la page du build et installe-le sur le téléphone.

## Structure
- `res/values/colors.xml` : les couleurs de la charte (seule source).
- `res/font/` : Barlow et Barlow Condensed (licence OFL, voir FONTS-OFL.txt).
- `Ui.kt` : composants de base (carte, carte héros, boutons, badge, ligne de liste, tuile, jauge, fenêtre du bas, logo).
- `MainActivity.kt` : écrans Accueil et Effort.
- `BleTreadmill.kt` : le faux tapis Bluetooth.
