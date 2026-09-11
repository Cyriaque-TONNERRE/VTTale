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

L'API Hytale fournit le bon point d'accroche : `PluginBase.shutdown(boolean)`
est appelé par `PluginManager.shutdown()` sur chaque plugin dans l'état
ENABLED, **pendant que le monde, le bus et les registres Hytale sont encore
vivants** (avant `eventBus.shutdown()`). Ensuite seulement,
`PluginBase.cleanup()` démonte les enregistrements Hytale du plugin (events,
commandes). VTTale n'override pas `shutdown()` aujourd'hui.

## Décisions

| Décision | Choix |
|---|---|
| Point d'accroche | Override de `PluginBase.shutdown()` dans `VTTaleHytalePlugin` → `modules.disableAll()`. Le hook JVM Runtime est supprimé |
| Où sauvegarder | `Module.onDisable()` EST le point de save : pendant `disableAll()`, les services restent enregistrés (le kernel n'a pas d'unregister) et le bus fonctionne. La Javadoc de `Module` le dit explicitement |
| Fermeture du registre | `SimpleModuleRegistry` gagne un drapeau `closed` : après `disableAll()`, `registerModule` logue une ERROR et refuse. Un module activé sur un kernel mort ne recevrait jamais d'`onDisable`, et son id pourrait être repris |
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

`setup()` perd son `Runtime.addShutdownHook` ; la Javadoc de la classe
(n'est-plus : « setup() registers a shutdown hook ») est mise à jour.

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

@Override
public synchronized void disableAll() {
    ... // inchangé
    closed = true;
}
```

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
- Ne pas y toucher aux enregistrements Hytale d'un plugin tiers : son
  `cleanup()` a déjà tourné (voir section ci-dessus).

## Tests

1. `SimpleModuleRegistryTest` — idempotence : `disableAll()` deux fois,
   chaque module voit exactement un `onDisable`.
2. `SimpleModuleRegistryTest` — fermeture : après `disableAll()`,
   `registerModule` est refusé (pas d'`onEnable`, id non réservé).

`platform/hytale` n'est pas testé unitairement (règle projet) ; validation
en jeu : stop serveur → « VTTale shut down » apparaît dans les logs avant la
fin de la séquence d'arrêt Hytale.

## Docs

- `docs/architecture.md` / `docs/architecture.fr.md` : section lifecycle —
  shutdown via `PluginBase.shutdown()`, règle save-in-onDisable, note
  d'ordre inter-plugins pour les tiers.
- Javadoc `VTTaleHytalePlugin` (suppression de la mention shutdown hook) et
  `Module.onDisable` (contrat ci-dessus).