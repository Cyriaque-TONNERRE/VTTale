# Design — Framework VTTale

Date : 2026-09-09
Statut : validé (design approuvé, sans tests unitaires)

## Contexte

VTTale renaît comme **framework de tabletop virtuel pour serveurs Hytale**. L'objectif n'est pas une application fonctionnelle mais une **plateforme extensible** : n'importe qui doit pouvoir écrire un plugin (module) en réutilisant les briques existantes (event bus, tokens, behaviors, commandes, services), sans jamais modifier le noyau.

Base de départ : la branche `poc/VTT-38-Token-Registry` de `VTTaleTeam/VTTale` (architecture validée : api pure, kernel, modules, platform). Les lessons du dépôt `TTTALE` (build moderne, bus à priorités, raffinements token) sont portées. Le dépôt TTALE n'est plus maintenu ; il sert de référence.

## Décisions structurantes

| Décision | Choix |
|---|---|
| Repo | Fork `VTTaleTeam/VTTale` → `Cyriaque-TONNERRE/VTTale`, base `poc/VTT-38-Token-Registry` |
| Identité | `org.vttale.vttale.*` (packages POC conservés), mod id `vttale`, JAR `VTTale-x.y.jar` |
| Build | Java 25, plugin Gradle `com.azuredoom.hytale-tools` sur `platform:hytale` uniquement |
| Déploiement | **Un seul JAR plugin** qui embarque api + kernel + modules intégrés + gamesystem |
| Plugins tiers | JAR Hytale séparé, `Dependencies: VTTALE:vttale=*`, auto-enregistrement dans `setup()` |
| Tests | Aucun test unitaire (choix explicite). Validation en jeu : JAR → `%APPDATA%\Hytale\UserData\Mods` |

> Les classloaders Hytale sont isolés par JAR (`PluginClassLoader`, accès aux deps de manifest via bridge). Conséquence : aucun mécanisme de découverte cross-JAR n'existe — les modules tiers s'auto-enregistrent dans leur `setup()`, les modules embarqués sont enregistrés explicitement par le platform.

## Layout Gradle

```
VTTale/
├── api/                 # contrats purs, org.vttale.vttale.api — ZÉRO import Hytale
├── kernel/              # impls simples (VTTaleKernel, SimpleEventBus, registries)
├── module/              # modules intégrés : chat, diceroll, token (impls)
├── gamesystem/          # dnd5e — exemple de système de jeu (simple Module)
└── platform/hytale/     # LE plugin Hytale : VTTale-x.y.jar (tout embarque, implementation deps)
```

## Abstractions core

1. **`VTTale.getKernel()`** — façade statique ; le platform crée le kernel (`new VTTaleKernel()`) et l'injecte via `VTTale.init(kernel)`. Pas de SPI : une seule impl, nous.
2. **`Kernel`** — conteneur de services extensible : `getEventBus()`, `getCommandRegistry()`, `getModuleRegistry()`, `getService(Class<T>)`, `registerService(Class<T>, T)`. Toute capacité nouvelle (TokenRegistry, BehaviorDispatcher…) est un service posé par un module — jamais un accesseur codé en dur dans le noyau.
3. **`Module`** — `onEnable(Kernel)` / `onDisable()`, l'unique point d'extension :
   - modules embarqués → enregistrés explicitement par le platform (`registerModule(new ChatModule())`, …) : liste lisible, ordre contrôlé ;
   - modules tiers → `VTTale.getKernel().getModuleRegistry().registerModule(...)` dans le `setup()` de leur plugin Hytale.
4. **`EventBus`** — synchrone et typé : `publish(event, EventContext)` / `subscribe(Class, BiConsumer)`, avec **priorités** (ordre croissant, le plus bas d'abord, égalité = ordre d'abonnement ; un publish ne rend la main qu'après tous les handlers). `EventContext` porte le `senderId` (UUID joueur, `"CONSOLE"`, `"KERNEL"`).
5. **Commandes** — `kernel.getCommandRegistry().registerCommand(name, description[, options])` → publie `RegisterCommandRequest` → l'`HytaleAdapter` binde au système de commandes Hytale (avec rattrapage des commandes enregistrées avant son activation) ; les réponses routent via `PlatformBroadcastEvent` (CONSOLE ou `PlayerRef`). Les modules ne voient jamais l'API Hytale.
6. **Token API** — `Token` = identité + **Components** (données) + **tags** (filtres) + **Behaviors** (logique) :
   - `Component` : données game-system posées par les modules (`record StatBlock(...) implements TokenComponent`) ;
   - **`Behavior`** : logique attachable (`onAttach`/`onDetach`/`onEvent`), état privé dans son `BehaviorContext` (KV typé), identifié `namespace:name` ;
   - **`BehaviorEvent`** : événement de jeu ciblant `targetToken`/`sourceToken`, annulable par convention (`isCancelled()`) ;
   - **`BehaviorDispatcher`** (service) : `dispatch`, `dispatchTo(event, token(s))`, `broadcast`, listeners globaux.
   - Une « réaction » (opportunity attack…) n'est donc **pas** un module du noyau : c'est un Behavior qu'un système de jeu attache aux tokens. Aucun listener figé.
7. **Événements de cycle de vie token** — `TokenCreatedEvent`, `TokenRemovedEvent`, `TokenUpdatedEvent`, `TokenBoundEvent` **uniquement** : déplacement, sélection, position passent par `TokenUpdatedEvent` (pas d'événements dédiés `Moved/Selected/Placed`).

## Flux d'exemple

```
/token move X → CommandRegistry → CommandExecutedEvent
→ module token : met à jour le token → publish TokenUpdatedEvent
→ chaque module réagit librement à l'événement bus
→ logique métier ciblée = BehaviorDispatcher.dispatch(MonBehaviorEvent)
  → behaviors attachés aux tokens concernés (état privé, annulable)
```

## Parcours d'un dev tiers

1. **Code** : `class MonModule implements Module` — composants, behaviors, événements, tout Java pur : api, kernel et modules ne dépendent d'aucune classe Hytale et s'exécutent hors serveur.
2. **Livraison** :
   - **JAR tiers** (cas standard) : plugin Hytale avec manifest `Dependencies: VTTALE:vttale=*` ; dans `setup()` : enregistrement du module. Le classloader bridge rend api/kernel visibles.
   - **Embarqué** : contribution au repo → classe référencée dans la liste d'enregistrement du platform.
3. **Réutilisation** : services kernel (`getService(TokenRegistry.class)`, `getService(BehaviorDispatcher.class)`), événements des autres modules, CommandRegistry pour ses commandes.

## Gestion d'erreurs

- Bus : exception dans un handler → loggée via le logger du platform (pas de `printStackTrace`), handlers suivants exécutés quand même.
- `Module.onEnable` qui jette → module ignoré, log d'erreur, le serveur démarre. Un module cassé ne tue jamais le serveur.

## Portage depuis TTALE

- Wrapper Gradle + conf `com.azuredoom.hytale-tools` / Java 25 / `gradle.properties` (identité mod) — sur `platform:hytale` uniquement.
- Priorités du bus (logique de tri + contrat « publish synchrone »).
- Raffinements token : `TokenQuery` (filtres type/tag/owner/range), registry avec événements de cycle de vie.
- Logique de lancer de dés (`Dice`) pour le module diceroll.
- Convention doc : `docs/` en français, un README par module, mis à jour à chaque changement d'architecture.
- **Sans** les tests JUnit TTALE (supprimés du périmètre).

## README (crédits)

Créditer les contributeurs du projet original et ses sources d'inspiration :
[Sparky200](https://github.com/Sparky200), [PhoenixEpic](https://github.com/PhoenixEpic), [giopalma](https://github.com/giopalma), et le projet [VTTaleTeam/VTTale](https://github.com/VTTaleTeam/VTTale).

## Hors périmètre (cette itération)

- Tests unitaires / JUnit (choix explicite).
- Événements dédiés `TokenMoved/Selected/Placed` (passent par `TokenUpdatedEvent`).
- Binding visuel des tokens au monde Hytale (au-delà du squelette `HytaleAdapter`/binder existant du POC).
- Système de jeu D&D5e réel (le module `gamesystem` reste un squelette d'exemple).
