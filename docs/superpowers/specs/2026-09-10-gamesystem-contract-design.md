# Design — GameSystem comme contrat distinct de Module

Date : 2026-09-10
Statut : validé (design approuvé)

## Contexte

Aujourd'hui `DND5EGameSystem implements Module` avec un `onEnable` vide : un système de jeu est indistinguable de n'importe quel module. Le noyau ne peut répondre à aucune de ces questions :

- quel système de jeu est actif ?
- deux systèmes peuvent-ils être chargés en même temps ?
- ce composant (fiche) appartient-il au 5e ou à autre chose ?

Un contrat `GameSystem` enregistré comme service répond aux trois, colle à la règle « une nouvelle capacité est un service enregistré par un module », et ne demande aucun mécanisme de découverte.

## Décisions

| Décision | Choix |
|---|---|
| Contrat | `GameSystem extends Module` dans `api` (`org.vttale.vttale.api.gamesystem`) — un système de jeu *est* un module qui porte ses métadonnées |
| Exclusivité | **Un seul GameSystem actif par serveur** ; le second module est **refusé avant tout effet de bord** |
| Mécanisme de refus | Garde dans `SimpleModuleRegistry.registerModule` **avant** `onEnable` (voir « Pourquoi pas un registerService strict ») |
| Enregistrement | Pattern DiceService : le GameSystem fait `kernel.registerService(GameSystem.class, this)` dans son `onEnable` |
| Sémantique du conteneur | `VTTaleKernel.registerService` inchangé (`put` simple) — la garde d'exclusivité suffit, pas de double mécanique |

## Le contrat

```java
package org.vttale.vttale.api.gamesystem;

public interface GameSystem extends Module {
    /** Stable ruleset identifier, e.g. "dnd5e". */
    String id();

    /** Ruleset version. */
    String version();

    /** Token component types this system owns (for ownership queries). */
    Set<Class<? extends TokenComponent>> components();
}
```

Zéro import Hytale. Étendre `Module` conserve le cycle de vie (commandes, events, `onDisable`) et laisse la plateforme inchangée : `modules.registerModule(new DND5EGameSystem())` reste valide.

## Réponses aux trois questions

- **Quel système actif ?** → `kernel.getService(GameSystem.class)`
- **Deux à la fois ?** → impossible : le second module est refusé avant initialisation
- **Ce composant est au 5e ?** → `gs.components().contains(StatBlock.class)` (slot unique, donc la propriété se réduit à l'appartenance)

## Exclusivité : pourquoi pas un registerService strict

Le réflexe initial — `putIfAbsent` + exception sur doublon dans `VTTaleKernel.registerService` — produit un **module fantôme à init partielle** combiné au catch-and-skip de `SimpleModuleRegistry` :

1. `onEnable` du second système enregistre une commande et des listeners (effets permanents : pas d'`unsubscribe` sur `EventBus`, pas d'`unregisterCommand`) ;
2. `registerService` lève ;
3. le catch logge et **n'ajoute pas le module** → `onDisable` jamais appelé.

Résultat : un système fantôme qui réagit aux events sans être chargé. L'init d'un module n'étant pas transactionnelle, le refus doit précéder tout effet de bord.

```java
// SimpleModuleRegistry.registerModule, avant onEnable
if (module instanceof GameSystem && kernel.getService(GameSystem.class) != null) {
    LOGGER.log(Level.ERROR, "A game system is already active ("
            + kernel.getService(GameSystem.class).id() + "); refusing "
            + module.getClass().getName());
    return;
}
```

- Le second GameSystem est refusé **en bloc** : zéro commande, zéro listener, rien à annuler.
- Référence kernel → `api.gamesystem.GameSystem` légale (kernel implémente l'api) ; « un seul système actif » est une politique de **cycle de vie**, le rôle de `ModuleRegistry`. Ce n'est pas un accesseur codé en dur : la lecture reste `getService(GameSystem.class)`.
- Effet de bord positif : protège **tous** les slots de services contre le vol par un doublon de module.

## Implémentation touchée

| Fichier | Changement |
|---|---|
| `api/.../gamesystem/GameSystem.java` | nouveau contrat |
| `gamesystem/.../dnd5e/DND5EGameSystem.java` | `implements GameSystem`, `id()`/`version()`/`components()`, auto-enregistrement en `onEnable` |

`DND5EGameSystem` : `id() = "dnd5e"`, version du ruleset, `components()` retourne un set vide tant qu'aucun composant dnd5e n'existe (premiers candidats : `StatBlock`, fiche de personnage).
| `kernel/.../SimpleModuleRegistry.java` | garde d'exclusivité avant `onEnable` |
| `docs/architecture.md` | section systèmes de jeu |

## Tests (JUnit 5, requis sur api/kernel/gamesystem)

- **kernel** : `registerService` reste permissif ; `registerModule` refuse un second `GameSystem` quand un service est déjà présent (premier préservé, module absent de la liste) ; accepte un `GameSystem` quand aucun n'est actif ; un module non-GameSystem n'est pas affecté.
- **gamesystem** : `DND5EGameSystem.onEnable` s'enregistre sous `GameSystem.class` avec son id/version/composants.

## Limites connues (hors périmètre)

- Tout module qui lève au milieu de `onEnable` laisse un état partiel (pas d'`unsubscribe`/`unregisterCommand` pour rollback). Préexistant ; à traiter le jour où ces API existent — un test `KNOWN LIMITATION` pourra l'épingler.
- Pas de `GameSystemRegistry`, pas d'événement dédié (`GameSystemActivatedEvent` — YAGNI, la lecture du service suffit). Ajouter la registry multi-systèmes le jour où un serveur fait tourner deux rulesets en parallèle.
