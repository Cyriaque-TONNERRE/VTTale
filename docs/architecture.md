# Architecture de VTTale

VTTale est un **framework** : le noyau fournit les briques (bus, tokens,
behaviors, commandes, services), le gameplay s'écrit en modules sans jamais
modifier le noyau.

> **Source de vérité** : [`docs/superpowers/specs/2026-09-09-framework-design.md`](superpowers/specs/2026-09-09-framework-design.md).
> Ce document est un résumé pour les auteurs de modules ; en cas de divergence,
> la spec prime.

## Vue d'ensemble

Un seul JAR plugin Hytale embarque tout le framework :

```
VTTale/
├── api/                 # contrats purs, org.vttale.vttale.api — ZÉRO import Hytale
├── kernel/              # impls simples (VTTaleKernel, SimpleEventBus, registries)
├── module/              # modules intégrés : chat, diceroll, token
├── gamesystem/          # dnd5e — squelette d'exemple de système de jeu
└── platform/hytale/     # LE plugin Hytale : bootstrap + pont vers l'API du jeu
```

Seul `platform/hytale` connaît l'API Hytale. `api`, `kernel`, `module` et
`gamesystem` sont du Java pur, exécutables hors serveur.

## Noyau : façade + conteneur de services

`VTTale.getKernel()` est une façade statique : le platform crée le kernel
(`new VTTaleKernel()`) et l'injecte via `VTTale.init(kernel)`. Pas de SPI —
une seule implémentation, la nôtre.

`Kernel` est un **conteneur de services extensible** :

- fixes : `getEventBus()`, `getCommandRegistry()`, `getModuleRegistry()` ;
- extensible : `getService(Class<T>)` / `registerService(Class<T>, T)`.

Toute capacité nouvelle (`TokenRegistry`, `BehaviorDispatcher`, …) est un
**service posé par un module** — jamais un accesseur codé en dur dans le
noyau. Un module qui ajoute une capacité la publie ainsi, les autres la
consomment avec `getService(...)`.

## Modules : cycle de vie et enregistrement

`Module` est l'unique point d'extension : `onEnable(Kernel)` / `onDisable()`,
plus deux déclarations optionnelles :

- **`id()`** — identifiant stable, unique parmi tous les modules installés ;
  le registre refuse un doublon. Default : nom de classe pleinement qualifié.
  Overridez avec un id namespacé (`"vttale:chat"`) pour des logs lisibles.
  Une classe de module est un singleton par serveur : deux instances de la
  même classe partagent le même id par défaut, la seconde est refusée.
- **`requires()`** — services qui doivent exister avant l'activation, lus via
  `getService` dans `onEnable` (ex. `Set.of(DiceService.class)`). Vide par
  défaut.

**L'ordre d'enregistrement n'a plus d'importance pour ces dépendances** : un
module dont les services requis manquent est mis en parc et s'active au
moment où ils apparaissent. En fin de démarrage, la plateforme logge en
ERROR les modules encore en attente avec les services manquants — un module
qui ne s'active jamais a un service requis jamais arrivé.

Le couplage par événements n'est **pas** une dépendance : abonnement à
l'activation, publication à l'exécution, aucun ordre contraint. Ne déclarez
jamais un événement en `requires()`.

Deux modes d'enregistrement :

| Mode | Qui | Comment |
|---|---|---|
| **Embarqué** | le platform | `registerModule(new ChatModule())`, … — liste lisible ; seul `HytaleAdapter` doit rester premier (pont de commandes, invisible à `requires()`) |
| **Tiers** | le plugin Hytale du module | dans son `setup()` : `VTTale.getKernel().getModuleRegistry().registerModule(new MonModule())` |

Les classloaders Hytale sont isolés par JAR : aucun mécanisme de découverte
cross-JAR n'existe. L'auto-enregistrement dans `setup()` est donc le seul
chemin pour un module tiers.

## Systèmes de jeu

Un système de jeu (D&D 5e, Pathfinder 2e, …) est un `GameSystem` :
un `Module` qui porte en plus son identité — `id()`, `version()`,
`components()` (types de composants possédés, informatif pour l'instant).

**Un seul actif par serveur.** `SimpleModuleRegistry` refuse un second
`GameSystem` **avant** son `onEnable` : zéro effet de bord, log d'erreur,
le serveur démarre. Le contrat est lu ainsi :

- système actif : `kernel.getService(GameSystem.class)` ;
- enregistrement : le système fait `kernel.registerService(GameSystem.class, this)`
  dans son `onEnable` (pattern DiceService) — **sans cet enregistrement, la garde
  d'exclusivité ne détecte pas votre système**.

Écrire un système de jeu = écrire un module qui implémente `GameSystem`
au lieu de `Module` ; le reste (commandes, events, behaviors, services)
ne change pas.

## Bus d'événements

Synchrone et typé : `publish(event, EventContext)` /
`subscribe(Class, BiConsumer)`, avec **priorités** (ordre croissant, le plus
bas d'abord ; égalité = ordre d'abonnement). Un `publish` ne rend la main
qu'après tous les handlers — une action de table (lancer → appliquer →
notifier) se termine toujours avant que l'appelant continue.

`EventContext` porte le `senderId` : UUID joueur, `"CONSOLE"` ou `"KERNEL"`.

## Flux des commandes

Les modules ne voient jamais l'API Hytale :

```
CommandRegistry.registerCommand(name, description[, options])
→ publie RegisterCommandRequest
→ HytaleAdapter binde au système de commandes Hytale
  (avec rattrapage des commandes enregistrées avant son activation)
→ CommandExecutedEvent (le module reçoit l'invocation)
→ réponses via PlatformBroadcastEvent (CONSOLE ou PlayerRef)
```

## Modèle token

`Token` = identité + **Components** + **tags** + **Behaviors** :

- **Components** = données game-system posées par les modules
  (`record StatBlock(...) implements TokenComponent`). Rien que des données.
- **tags** = filtres pour les requêtes (`TokenQuery` : type/tag/owner/range).
- **Behaviors** = logique attachable (`onAttach`/`onDetach`/`onEvent`),
  identifiés `namespace:name`. Leur état est **privé**, dans leur
  `BehaviorContext` (KV typé) — jamais sur le token.
- **`BehaviorEvent`** : événement de jeu ciblant `targetToken`/`sourceToken`,
  annulable par convention (`isCancelled()`).
- **`BehaviorDispatcher`** (service) : `dispatch`, `dispatchTo(event, tokens)`,
  `broadcast`, listeners globaux.

Une « réaction » (opportunity attack…) n'est donc pas un module du noyau :
c'est un Behavior qu'un système de jeu attache aux tokens. Aucun listener figé.

### Événements de cycle de vie token

`TokenCreatedEvent`, `TokenRemovedEvent`, `TokenUpdatedEvent`,
`TokenBoundEvent` — **c'est tout**. Déplacement, sélection, position et toute
autre mutation passent par `TokenUpdatedEvent` : pas d'événements dédiés
`Moved`/`Selected`/`Placed`.

Flux type :

```
/token move X → CommandRegistry → CommandExecutedEvent
→ module token : met à jour le token → publish TokenUpdatedEvent
→ chaque module réagit librement à l'événement bus
→ logique métier ciblée = BehaviorDispatcher.dispatch(MonBehaviorEvent)
  → behaviors attachés aux tokens concernés (état privé, annulable)
```

## Gestion d'erreurs

- **Bus** : une exception dans un handler est loggée via le logger du platform
  (jamais `printStackTrace`), les handlers suivants s'exécutent quand même.
- **`Module.onEnable` qui jette** : le module est ignoré, log d'erreur, le
  serveur démarre. Un module cassé ne tue jamais le serveur.
- **`requires()` jamais satisfaites** : le module reste parqué (log INFO à la
  mise en parc) ; rapport ERROR en fin de démarrage listant les services
  manquants. Un module parqué ne voit jamais `onEnable` ni `onDisable`.
- **Second `GameSystem` refusé** : refusé avant tout effet de bord (pas
  d'`onEnable` du tout), log d'erreur, le premier système reste actif.

## Parcours d'un dev tiers

1. **Code** : `class MonModule implements Module` — composants, behaviors,
   événements, tout Java pur : api, kernel et modules ne dépendent d'aucune
   classe Hytale et s'exécutent hors serveur.
2. **Livraison** :
   - **JAR tiers** (cas standard) : plugin Hytale avec manifest
     `Dependencies: VTTALE:vttale=*` ; dans `setup()` : enregistrement du
     module. Le classloader bridge rend api/kernel visibles.
   - **Embarqué** : contribution au repo → classe référencée dans la liste
     d'enregistrement du platform.
3. **Réutilisation** : services kernel (`getService(TokenRegistry.class)`,
   `getService(BehaviorDispatcher.class)`), événements des autres modules,
   CommandRegistry pour ses commandes.
