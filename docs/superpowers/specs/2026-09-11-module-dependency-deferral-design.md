# Design — Dépendances de modules : `id()`, `requires()` et activation différée

Date : 2026-09-11
Statut : validé (design approuvé)

## Contexte

L'ordre de chargement des modules n'est écrit nulle part. Il est encodé dans cinq lignes de `VTTaleHytalePlugin.setup()` : `HytaleAdapter` en premier pour rattraper les commandes, `TokenModule` avant la construction de `HytaleTokenBinder`, `ChatModule` avant que `DiceRollModule` puisse répondre. Entre plugins tiers, c'est pire : `VTTALE:vttale=*` garantit que VTTale démarre en premier, mais rien n'ordonne le plugin X par rapport au plugin Y. Si le module de Y a besoin d'un service que X enregistre, `getService` renvoie `null` et le tiers prend une NPE dans son code — le pire endroit pour un bug de framework.

Aujourd'hui `Module` ne déclare ni identité ni dépendance, et `SimpleModuleRegistry.registerModule` active immédiatement, quoi qu'il manque.

## Décisions

| Décision | Choix |
|---|---|
| Contrat de dépendance | `Module.requires()` → `Set<Class<?>>` de **services** — dans ce framework, le service est le canal de communication, donc la dépendance réelle est presque toujours un service. Pas d'ids de modules dans `requires()` : un mécanisme, pas deux |
| Identité | `Module.id()` → identifiant stable, clé du dédoublonnage et des logs |
| Activation | **Différée réactive** : un module dont les services requis manquent est parqué ; il s'active quand ils apparaissent. Pas de tri topologique — l'ordre émerge au lieu d'être calculé |
| Déclencheur | Après chaque activation réussie **et** à chaque `registerService` (garde anti-réentrance, voir plus bas) |
| Rapport | Fin de `setup()` : les modules encore parqués sont loggés en **ERROR** avec les services manquants. La mise en attente elle-même est en **INFO** (normal et attendu) |
| Verrouillage | `registerModule` reste `synchronized` ; le drain prend le même moniteur. `VTTaleKernel` type son champ en `SimpleModuleRegistry` |

## Le contrat

```java
public interface Module {

    /**
     * Stable identifier, unique across all installed modules: the registry
     * refuses a module whose id is already taken (enabled or pending).
     * Default: fully qualified class name — unique across packages. Override
     * with a namespaced id ("vttale:chat") for readable logs.
     * A module class is therefore a singleton per server: two instances of
     * the same class collide on the same default id.
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * Services that must be registered before this module can enable.
     * Empty by default. Registration order stops mattering: the registry
     * parks the module until every entry resolves via getService().
     */
    default Set<Class<?>> requires() {
        return Set.of();
    }

    void onEnable(Kernel kernel);

    default void onDisable() {
    }
}
```

- `GameSystem.id()` existe déjà — même signature, il satisfait `Module.id()` sans changement de code. En revanche son Javadoc change : « Namespace it if collisions ever matter » devient une obligation (`id()` est désormais la clé de dédoublonnage, les collisions comptent immédiatement).
- Zéro rupture pour l'existant : les deux méthodes sont des defaults, les built-ins ne changent pas.

## Mécanique du registre (`SimpleModuleRegistry`)

Un seul chemin d'activation, `tryEnable(module)`, utilisé par les deux entrées (enregistrement direct et drain des parqués) : dédoublonnage, garde GameSystem, try/catch, `modules.add`. Tout ce qui existe déjà y reste.

```java
synchronized registerModule(module):
    if modules.contains(module) or pending.contains(module): return   // inchangé (identité)
    String id = safeId(module);          // id() qui jette -> ERROR, refus
    if id == null: return
    if idsTaken(id):                     // l'id de chaque module est enregistré ici,
        ERROR "module id already taken"; return   // qu'il finisse activé ou parqué
    Set<Class<?>> missing = safeRequires(module);   // requires() qui jette -> ERROR, refus
    if missing == null: return
    if !missing.isEmpty():
        pending.add(module);             // ArrayList, ordre d'enregistrement
        INFO "deferred, waiting for " + missing; return
    tryEnable(module);                   // se termine par drainPending()

synchronized tryEnable(module):
    // garde GameSystem inchangée (refus avant tout effet de bord)
    try { module.onEnable(kernel); } catch (Throwable e) { ERROR; return; }  // inchangé
    modules.add(module);
    drainPending();

synchronized drainPending():
    if draining: dirty = true; return
    draining = true;
    try {
        do {
            dirty = false;
            // itération dans l'ordre d'enregistrement -> déterministe
            for (parqué dont les requires résolvent):
                pending.remove(module); tryEnable(module);
        } while (dirty);                 // les chaînes A -> B -> C se déroulent ici
    } finally { draining = false; }

// appelé par VTTaleKernel.registerService après le put
void onServiceRegistered():
    drainPending();
```

### Pourquoi le garde anti-réentrance

Chaîne naïve : `registerModule` → `onEnable` → `registerService` → drain → `tryEnable(parqué)` → `onEnable(parqué)`. Tout sur le même thread, pendant que le `onEnable` du premier module n'a pas rendu la main. `TokenModule` illustre le trou :

```java
kernel.registerService(TokenRegistry.class, registry);      // <- un parqué sur TokenRegistry s'activerait ICI
kernel.registerService(BehaviorDispatcher.class, dispatcher);
```

Le dépendant démarrerait dans un monde où `TokenRegistry` existe mais pas `BehaviorDispatcher`, et `modules.add(TokenModule)` n'ayant pas encore eu lieu, la liste recevrait le dépendant avant son fournisseur — ce qui déciderait silencieusement de l'ordre de `disableAll()`. Le verrou réentrant ne protège pas : c'est le même thread, il passe.

Le garde : **ne jamais drainer pendant qu'une activation est en cours**. En cours de drain, `onServiceRegistered` ne fait que coucher le drapeau `dirty` ; le drain le plus externe re-boucle après que `onEnable` a rendu la main et que le module a été ajouté à la liste. L'activation est atomique du point de vue des parqués.

Le garde est « pas d'activation en cours → drainer maintenant », pas seulement « reporter au drain externe » : un service peut aussi naître **hors** de tout `onEnable` (le `setup()` plateforme, ou un plugin tiers qui fait `kernel.registerService` sans module). Dans ce cas personne d'autre ne drainerait, et le parqué dormirait jusqu'au prochain `registerModule`.

### Verrouillage

- `registerModule`, `tryEnable`, `drainPending`, `disableAll` : moniteur du registre (réentrant — un module qui en enregistre un autre reste correct, comme aujourd'hui).
- `registerService` du kernel appelle désormais `onServiceRegistered` → prend le moniteur du registre. Ce n'est plus un `put` trivial, mais pas d'interblocage : rien n'est appelé en tenant un autre verrou, et c'est du boot-time. `VTTaleKernel` type son champ en `SimpleModuleRegistry` (reste interne au kernel — `getModuleRegistry()` continue d'exposer l'interface).
- `pending`, `ids` : état simple sous le même moniteur ; `modules` reste `CopyOnWriteArrayList` par prudence vis-à-vis des itérations hors verrou, mais tout accès passe désormais sous moniteur.

## Rapport de fin de démarrage

`SimpleModuleRegistry.reportPendingModules()` (méthode kernel-interne, **pas** dans l'interface `ModuleRegistry`) : logge en ERROR chaque module encore parqué avec les services manquants, puis ne fait plus rien. Appelé par la plateforme en fin de `setup()`, juste avant la vérification du binder token. Le parking courant reste en INFO : bruyant quand tout va bien était l'inverse de ce qu'il faut.

## Ce qui ne change pas

- `HytaleAdapter` reste premier dans `setup()` : il ne fournit aucun service, il ponte les events Hytale vers le bus kernel — les dépendances d'événements sont invisibles à `requires()`. Ça reste une convention plateforme documentée.
- Le binder token et sa vérification `null` : inchangés.
- La garde d'exclusivité GameSystem : inchangée, rejouée à l'identique quand un GameSystem arrive via le drain. Deux GameSystems parqués sur le même service → le drain les active dans l'ordre d'enregistrement, le premier publie son service, le second est refusé par la garde — déterministe.
- Le contrat d'échec : un module refusé ou en échec d'`onEnable` ne reçoit jamais `onDisable`. Les parqués n'ont jamais vu `onEnable`, donc jamais `onDisable`.

## Implémentation touchée

| Fichier | Changement |
|---|---|
| `api/.../module/Module.java` | `id()` et `requires()` en default, Javadoc du contrat |
| `api/.../module/ModuleRegistry.java` | Javadoc : parc des parqués, refus par id, rapport |
| `api/.../gamesystem/GameSystem.java` | Javadoc d'`id()` : « collisions ever matter » → obligation d'unicité |
| `kernel/.../module/SimpleModuleRegistry.java` | parc `pending`, `tryEnable`, `drainPending` avec garde anti-réentrance, `onServiceRegistered`, `reportPendingModules`, dédoublonnage par id, `disableAll` vide `pending` |
| `kernel/.../VTTaleKernel.java` | champ typé `SimpleModuleRegistry` ; `registerService` appelle `onServiceRegistered` |
| `platform/hytale/.../VTTaleHytalePlugin.java` | appel `reportPendingModules()` en fin de `setup()` (cast `SimpleModuleRegistry`, interne plateforme) |
| `docs/architecture.md` | guide auteur de module : déclare `requires()`, l'ordre d'enregistrement n'a plus d'importance ; unicité d'`id()` |

Aucun built-in ne déclare `requires()` : aucun n'a de dépendance de service réelle (DiceRollModule ne s'abonne qu'aux events). Aucun override cosmétique d'`id()` non plus — les ids ne servent pas à la résolution, les FQN distincts suffisent.

## Tests (JUnit 5, kernel)

- **Remplace** `distinctInstancesAreNotDeduplicated` (comportement volontairement changé) : deux instances de même classe → même id par défaut → la seconde est refusée, la première tourne. Un `id()` overridé distinct lève le conflit.
- **Parc** : module dont le service manque → pas d'`onEnable`, absent de la liste des modules, pas de `onDisable` au shutdown.
- **Inversion d'ordre** : consommateur enregistré avant fournisseur → les deux finissent activés, l'ordre d'activation est fournisseur d'abord.
- **Chaîne** : A requiert S1, B fournit S1 et requiert S2, C fournit S2 — enregistrés dans le désordre, tous activés, ordre respecté.
- **Service hors module** : `kernel.registerService` direct (sans `registerModule`) réveille un parqué — couvre la branche « pas d'activation en cours » du garde.
- **Atomicité du drain** : fournisseur dont l'`onEnable` enregistre S1 puis S2 ; consommateur `requires() = {S1, S2}` → le `onEnable` du consommateur observe `getService(S2) != null`. C'est le test qui garde la décision anti-réentrance.
- **`requires()` qui jette** → module refusé, rien d'autre n'explose. Idem `id()` qui jette.
- **Déterminisme GameSystem** : deux GameSystems parqués sur le même service → premier enregistré gagne, second refusé par la garde, jamais activé.
- Les tests GameSystem existants restent valides tels quels.

Le contenu des lignes de log (INFO/ERROR) n'est pas testé : tester les logs, c'est tester l'habillage.

## Limites connues (hors périmètre)

- **Le rapport ne couvre que le boot des built-ins.** Un module tiers parqué après le rapport (plugin chargé après VTTale) n'a que sa ligne INFO. Il n'existe pas de moment universel « tous les plugins sont là » côté kernel. **Déclencheur** : le pont Hytale s'abonne à un event server-ready et relance `reportPendingModules()` — une ligne, quand un cas réel le demande.
- **Collision FQN résiduelle** : deux JARs tiers chargés par des classloaders différents peuvent porter le même FQN. Remède documenté : override namespacé d'`id()`. Le default est meilleur que `getSimpleName()`, pas parfait.
- **`requires()` ne voit que les services** : les dépendances d'événements (HytaleAdapter d'abord) restent une convention plateforme.
- Pas d'API de consultation des parqués au-delà du rapport, pas de désactivation individuelle, pas d'`onServiceRegistered` dans l'interface publique. À ajouter quand un cas réel le demande.
