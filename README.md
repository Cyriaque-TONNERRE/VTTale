# VTTale

VTTale transforme un serveur Hytale en table de jeu de rôle (VTT). C'est un
**framework** : le noyau fournit les briques (bus d'événements, tokens,
behaviors, commandes, services) et tout le gameplay s'écrit en modules
par-dessus — sans jamais modifier le noyau.

## Crédits

VTTale renaît grâce au travail des contributeurs du projet original et de ses
sources d'inspiration :

- [Sparky200](https://github.com/Sparky200)
- [PhoenixEpic](https://github.com/PhoenixEpic)
- [giopalma](https://github.com/giopalma)
- Le projet [VTTaleTeam/VTTale](https://github.com/VTTaleTeam/VTTale), dont ce
  dépôt est le fork (branche `poc/VTT-38-Token-Registry`).

## Architecture

Un seul JAR plugin Hytale embarque tout :

| Module | Rôle |
|---|---|
| `api` | Contrats purs (`org.vttale.vttale.api`), zéro import Hytale |
| `kernel` | Implémentations simples (bus à priorités, registries, services) |
| `module` | Modules intégrés : chat, dés (`/roll`), tokens |
| `gamesystem` | Systèmes de jeu — `dnd5e` sert d'exemple de squelette |
| `platform/hytale` | Le plugin Hytale : bootstrap, pont vers l'API du jeu |

Voir `docs/architecture.md` pour le détail et le parcours « écrire un module ».

## Build

```bash
./gradlew build
```

Le JAR déployable est `platform/hytale/build/libs/VTTale-<version>-all.jar`.

## Déploiement (test en jeu)

1. Copier `VTTale-<version>-all.jar` dans `%APPDATA%\Hytale\UserData\Mods`
   (créer le dossier s'il manque).
2. Lancer Hytale → créer un monde → roue crantée → Mods → vérifier que
   **VTTale** est listé.
3. En jeu : `/roll 2d6+3` doit répondre dans le chat ; un joueur qui se
   connecte obtient un token (log serveur).

## Écrire un module tiers

1. Projet Java avec `org.vttale:vttale` (ou les sources de `api/`) en
   `compileOnly`.
2. Écrire `class MonModule implements Module` (composants, behaviors,
   événements — Java pur, testable hors Hytale).
3. Publier un plugin Hytale dont le `manifest.json` déclare
   `"Dependencies": { "VTTALE:vttale": "*" }` et dont le `Main` (classe
   étendant `JavaPlugin`) fait dans `setup()` :

```java
VTTale.getKernel().getModuleRegistry().registerModule(new MonModule());
```

Les classloaders Hytale étant isolés par JAR, l'auto-enregistrement est le seul
mécanisme de découverte inter-plugin.
