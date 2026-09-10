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
| Atomicité | `registerModule` devient `synchronized` — la garde est un check-then-act |
| Enregistrement | Pattern DiceService : le GameSystem fait `kernel.registerService(GameSystem.class, this)` dans son `onEnable` |
| Sémantique du conteneur | `VTTaleKernel.registerService` inchangé (`put` simple) — la garde d'exclusivité suffit, pas de double mécanique |

## Le contrat

```java
package org.vttale.vttale.api.gamesystem;

import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.token.TokenComponent;

import java.util.Set;

public interface GameSystem extends Module {
    /** Stable ruleset identifier, e.g. "dnd5e". Namespace it if collisions ever matter. */
    String id();

    /** Ruleset version. */
    String version();

    /**
     * Token component types this system contributes.
     * <p>
     * Informational only: no kernel behaviour reads this today. It becomes load-bearing
     * when several systems can coexist, or when persistence needs to route codecs.
     */
    Set<Class<? extends TokenComponent>> components();
}
```

Zéro import Hytale. Étendre `Module` conserve le cycle de vie (commandes, events, `onDisable`) et laisse la plateforme inchangée : `modules.registerModule(new DND5EGameSystem())` reste valide.

## Réponses aux trois questions

- **Quel système actif ?** → `kernel.getService(GameSystem.class)`
- **Deux à la fois ?** → impossible : le second module est refusé avant initialisation
- **Ce composant est au 5e ?** → la question est **sans objet tant que le slot est unique** : il y a au plus un système, donc tout composant de règles lui appartient ou n'appartient à aucun système. `components()` ne devient une vraie réponse qu'avec le multi-systèmes (voir Limites).

## Exclusivité : pourquoi pas un registerService strict

Le réflexe initial — `putIfAbsent` + exception sur doublon dans `VTTaleKernel.registerService` — produit un **module fantôme à init partielle** combiné au catch-and-skip de `SimpleModuleRegistry` :

1. `onEnable` du second système enregistre une commande et des listeners (effets permanents : pas d'`unsubscribe` sur `EventBus`, pas d'`unregisterCommand`) ;
2. `registerService` lève ;
3. le catch logge et **n'ajoute pas le module** → `onDisable` jamais appelé.

Résultat : un système fantôme qui réagit aux events sans être chargé. L'init d'un module n'étant pas transactionnelle, le refus doit précéder tout effet de bord.

```java
@Override
public synchronized void registerModule(Module module) {
    if (modules.contains(module)) {
        return;
    }
    // Exclusivity: at most one GameSystem per server. Refused BEFORE onEnable, so the
    // rejected module produces no side effect at all - nothing to roll back.
    if (module instanceof GameSystem candidate) {
        GameSystem active = kernel.getService(GameSystem.class);
        if (active != null) {
            LOGGER.log(Level.ERROR, "A game system is already active (" + active.id()
                    + "); refusing " + candidate.getClass().getName());
            return;
        }
    }
    try {
        module.onEnable(kernel);
    } catch (Throwable e) {
        LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                + " failed to enable and was skipped", e);
        return;
    }
    modules.add(module);
}
```

- Le second GameSystem est refusé **en bloc** : zéro commande, zéro listener, rien à annuler.
- Référence kernel → `api.gamesystem.GameSystem` légale (kernel implémente l'api) ; « un seul système actif » est une politique de **cycle de vie**, le rôle de `ModuleRegistry`. Ce n'est pas un accesseur codé en dur : la lecture reste `getService(GameSystem.class)`.
- **Portée réelle de la garde** : elle protège le seul slot `GameSystem`. Les autres services restent en dernier-écrit-gagne, conformément à la décision « `registerService` inchangé ». Un doublon de `DiceRollModule` écrase toujours silencieusement `DiceService`.

### Pourquoi `synchronized`

La garde est un check-then-act : lire `getService`, puis laisser `onEnable` poser le service. Deux plugins tiers s'auto-enregistrant depuis des threads différents passent tous deux le test et activent deux systèmes, dont un fantôme — exactement ce que la garde doit empêcher. `synchronized` referme la fenêtre, et corrige au passage la même course préexistante sur `contains(module)` / `add(module)`.

Coût : `onEnable` s'exécute sous le verrou. C'est une opération de démarrage, jamais chaude ; le verrou est réentrant, donc un module qui en enregistre un autre reste correct. À revoir seulement si un `onEnable` se met un jour à bloquer sur un autre thread.

## Implémentation touchée

| Fichier | Changement |
|---|---|
| `api/.../gamesystem/GameSystem.java` | nouveau contrat |
| `gamesystem/.../dnd5e/DND5EGameSystem.java` | `implements GameSystem`, `id()`/`version()`/`components()`, auto-enregistrement en `onEnable` |
| `gamesystem/build.gradle.kts` | ajouter `testImplementation(project(":kernel"))` — les tests montent un vrai `VTTaleKernel` ; arête test-only, absente du graphe de production |
| `kernel/.../SimpleModuleRegistry.java` | garde d'exclusivité avant `onEnable` + `synchronized` |
| `docs/architecture.md` | section systèmes de jeu |

`DND5EGameSystem` : `id() = "dnd5e"`, version du ruleset, `components()` retourne un set vide — aucun `TokenComponent` n'existe encore dans le code de production (premiers candidats : `StatBlock`, fiche de personnage).

## Tests (JUnit 5, requis sur api/kernel/gamesystem)

- **kernel — exclusivité** : `registerModule` refuse un second `GameSystem` quand le service est déjà présent (premier préservé, second absent de la liste des modules) ; accepte un `GameSystem` quand aucun n'est actif ; un module non-`GameSystem` n'est jamais affecté par la garde.
- **kernel — aucun effet de bord** : le `onEnable` du second `GameSystem` **n'est pas appelé du tout**. C'est le test qui garde la décision de conception : un `GameSystem` de test qui journalise son `onEnable` (et y enregistrerait commande + listener) ne doit rien journaliser.
- **kernel — permissivité conservée** : `VTTaleKernelTest#registrationOverwritesSilently` reste valide tel quel. Aucun test existant n'est à remplacer.
- **gamesystem** : `DND5EGameSystem.onEnable` s'enregistre sous `GameSystem.class` avec son id, sa version et ses composants ; `getService(DND5EGameSystem.class)` reste `null` (les services sont indexés par la classe déclarée).

## Limites connues (hors périmètre)

- Tout module qui lève au milieu de `onEnable` laisse un état partiel (pas d'`unsubscribe`/`unregisterCommand` pour rollback). Préexistant ; à traiter le jour où ces API existent — un test `KNOWN LIMITATION` pourra l'épingler.
- La garde connaît `GameSystem` par `instanceof` : le kernel importe pour la première fois un concept métier plutôt qu'une brique d'infrastructure. Acceptable pour **un** concept exclusif. **Déclencheur de généralisation** : le deuxième concept exclusif (un `MapProvider`, un `InitiativeTracker`…). La forme cible est alors `Module.provides()` — méthode par défaut retournant `Set.of()`, que la registry vérifie avant `onEnable` — qui rend la garde générique et le kernel de nouveau agnostique.
- Pas de `GameSystemRegistry`, pas d'événement dédié (`GameSystemActivatedEvent` — YAGNI, la lecture du service suffit). **Déclencheur** : avant la publication du premier module tiers qui lit `getService(GameSystem.class)`, pas « le jour où un serveur fait tourner deux rulesets » — après cette date, changer la forme casse le code des autres.
- `components()` part sans appelant. Si aucun consommateur n'apparaît d'ici l'arrivée de la persistance ou du multi-systèmes, le retirer de l'API publique avant qu'un tiers ne s'y appuie.