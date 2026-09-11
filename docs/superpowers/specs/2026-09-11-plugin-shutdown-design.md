# Design — Shutdown du plugin : brancher `disableAll` sur le lifecycle Hytale

Date : 2026-09-11
Statut : validé (design approuvé)

## Contexte

VTTale n'a pas de déchargement propre : `setup()` enregistre un
`Runtime.addShutdownHook(modules::disableAll)`. Ce hook tourne à la sortie du
JVM, c'est-à-dire après que Hytale a déjà tout démonté — trop tard pour
sauvegarder. Pire : `HytaleServer.shutdown0()` programme un `Runtime.halt()`
3 secondes après le début de l'arrêt, et le hook JVM tourne *dans* cette
fenêtre — un save lent peut être interrompu en pleine écriture.

L'API Hytale fournit le bon point d'accroche : `PluginManager.shutdown()`
appelle `shutdown0(true)` sur chaque plugin dans l'état ENABLED, qui appelle
à son tour la méthode protégée `PluginBase.shutdown()` — celle qu'on
override — **pendant que le monde, le bus et les registres Hytale sont encore
vivants** (avant `eventBus.shutdown()`). Ensuite seulement,
`PluginBase.cleanup()` démonte les enregistrements Hytale du plugin (events,
commandes). VTTale n'override pas `shutdown()` aujourd'hui.

## Décisions

| Décision | Choix |
|---|---|
| Point d'accroche | Override de `PluginBase.shutdown()` dans `VTTaleHytalePlugin` → `modules.disableAll()`. Le hook JVM Runtime est supprimé |
| Où sauvegarder | `Module.onDisable()` EST le point de save : pendant `disableAll()`, les services restent enregistrés (le kernel n'a pas d'unregister) et le bus fonctionne. La Javadoc de `Module` le dit explicitement |
| Fermeture du registre | `SimpleModuleRegistry` gagne un drapeau `closed`, levé **dès l'entrée** de `disableAll()` : ensuite, `registerModule` logue une ERROR et refuse, et `drainPending` n'active plus rien. Un module activé sur un kernel mort — ou pendant le démontage — ne recevrait jamais d'`onDisable`, et son id pourrait être repris |
| Idempotence | `disableAll()` deux fois = no-op (listes vides), aucun module ne voit `onDisable` deux fois — verrouillé par test |
| Ordre d'arrêt inter-plugins | `PluginManager.shutdown()` itère en reverse de `Mod.calculateLoadOrder` (tri topologique, dépendances d'abord) : les plugins **dépendants** sont arrêtés **avant** VTTale. Conséquence documentée, pas de code (voir « Ordre d'arrêt inter-plugins ») |
| Garde setup échoué | `shutdown()` teste `if (modules != null)` : `shutdown0(true)` n'est en principe appelé que sur un plugin ENABLED, mais la garde coûte une ligne |

## Le code

```java
public class VTTaleHytalePlugin extends JavaPlugin {

    private ModuleRegistry modules; // monte en champ, alimenté dans setup()

    @Override
    protected void shutdown() {
        if (modules == null) {
            return; // setup() failed before booting the kernel
        }
        modules.disableAll();
        getLogger().at(Level.INFO).log("VTTale shut down");
    }
}
```

`setup()` perd son `Runtime.addShutdownHook` ; la Javadoc de la classe, qui
mentionne encore « setup() registers a shutdown hook », est mise à jour.

Côté registre :

```java
private boolean closed = false;

@Override
public synchronized void registerModule(Module module) {
    if (closed) {
        LOGGER.log(Level.ERROR, "Module " + ... + " refused: the registry is closed");
        return;
    }
    ...
}

private synchronized void drainPending() {
    if (closed || activating > 0 || draining) { ... } // plus d'activation une fois fermé
    ...
}

@Override
public synchronized void disableAll() {
    closed = true; // AVANT la boucle, pas après
    ... // boucle onDisable inchangée
}
```

`closed` est levé **avant** la boucle d'`onDisable`, pas après. Les verrous
Java sont réentrants : un `onDisable` qui appelle `registerModule` (ou
`registerService`, qui déclenche `drainPending`) repasserait dans le registre
pendant le démontage. Avec `closed = true` en fin de méthode, ce module serait
activé en pleine boucle, puis effacé par `modules.clear()` sans jamais voir
son `onDisable`. C'est exactement le cas que la fermeture doit empêcher.

`closed` n'a pas de chemin de réouverture : le plugin boote une fois par JVM
(reload non supporté), le registre fermé le reste.

## Ordre d'arrêt inter-plugins (documentation, pas de code)

Vérifié dans les sources Hytale : `PluginManager.shutdown()` arrête les
plugins en ordre inverse du tri topologique de chargement, donc un plugin
tiers qui dépend de VTTale voit son `shutdown()` **puis son `cleanup()`**
avant les nôtres. Quand notre `disableAll()` appelle ensuite l'`onDisable`
de son module, ses enregistrements côté Hytale sont déjà démontés.

Deux cas :

- Sérialiser l'état du kernel (tokens, services) : ça marche, le kernel est
  encore intact. Cas principal — c'est ce que fait le save.
- Toucher à ses propres enregistrements Hytale dans `onDisable` : déjà trop
  tard. Le tiers doit faire ça dans le `shutdown()` de **son** plugin.

Pour ce correctif : la Javadoc de `Module.onDisable()` et le guide des
modules tiers (docs architecture, EN + FR) documentent la règle. Un
`unregisterModule` que le tiers appellerait dans son propre `shutdown()` est
la vraie solution — prévue pour plus tard, pas dans ce correctif.

## Contrat de `Module.onDisable` (Javadoc)

- Appelé une fois, en ordre inverse d'activation (dépendants avant
  fournisseurs) — jamais deux fois, même en cas de double `disableAll()`.
- C'est l'endroit où sauvegarder : services et bus encore fonctionnels.
- La sauvegarde doit être **rapide et synchrone** : le `Runtime.halt()`
  programmé par Hytale peut couper un I/O lent (voir « Point ouvert »).
- Ne pas y appeler `world.execute(...)` : une tâche mise en file d'attente
  sur le thread du monde peut ne jamais tourner pendant l'arrêt. Sérialiser
  l'état déjà présent dans le kernel, sans relire le monde.
- Ne pas y enregistrer de module ni de service : le registre est fermé.
- Ne pas y toucher aux enregistrements Hytale d'un plugin tiers : son
  `cleanup()` a déjà tourné (voir section ci-dessus).

## Tests

1. `SimpleModuleRegistryTest` — idempotence : `disableAll()` deux fois,
   chaque module voit exactement un `onDisable`.
2. `SimpleModuleRegistryTest` — fermeture : après `disableAll()`,
   `registerModule` est refusé (pas d'`onEnable`, id non réservé).
3. `SimpleModuleRegistryTest` — réentrance : un module qui appelle
   `registerModule(autre)` depuis son `onDisable` → `autre` ne voit jamais
   `onEnable`. Même chose pour un module parké réveillé par un
   `registerService` fait dans un `onDisable`.
4. `VTTaleKernelTest` — point de save : pendant `onDisable`, un module lit un
   service via `getService` et publie sur le bus, et un handler abonné le
   reçoit. C'est ce test qui garantit le contrat « onDisable = save ».

`platform/hytale` n'est pas testé unitairement (règle projet) ; validation
en jeu : stop serveur → « VTTale shut down » apparaît dans les logs **avant**
les lignes d'arrêt du bus d'événements Hytale (`eventBus.shutdown()`), pas
seulement « quelque part » dans la séquence d'arrêt.

## La fenêtre de 3 s — vérifiée

Vérifié dans `HytaleServer.shutdown0()` (sources décompilées 0.6.4) :
`pluginManager.shutdown()` (les plugins s'arrêtent) est appelé **avant** la
programmation du `Runtime.halt()` à 3 s — le timer n'est armé qu'à la fin de
la méthode, juste avant `System.exit()`. Donc :

- `onDisable` n'a **pas** de budget de 3 s ;
- en revanche, un `onDisable` qui bloque indéfiniment empêche `shutdown0()`
  d'atteindre le release de `aliveLock` : le serveur ne s'arrête jamais. Un
  blocage = serveur suspendu, pas données corrompues.

D'où la règle « save rapide et synchrone » de la Javadoc, qui reste
valable. La future spec de persistance retiendra l'écriture atomique
(fichier temporaire + `Files.move(..., ATOMIC_MOVE)`) comme défense au cas
où une version future de Hytale armerait le halt plus tôt.

## Docs

- `docs/architecture.md` / `docs/architecture.fr.md` : section lifecycle —
  shutdown via `PluginBase.shutdown()`, règle save-in-onDisable, note
  d'ordre inter-plugins pour les tiers.
- Javadoc `VTTaleHytalePlugin` (suppression de la mention shutdown hook) et
  `Module.onDisable` (contrat ci-dessus).
- `CLAUDE.md` : ajouter sous « Architecture », à côté de « Reload is not
  supported », une ligne « shutdown = `PluginBase.shutdown()` →
  `disableAll()` ; `onDisable` is the save point ».