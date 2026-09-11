# Design — Dépendances de modules : `id()`, `requires()` et activation différée

Date : 2026-09-11
Statut : validé (design approuvé)

## Contexte

L'ordre de chargement des modules n'est écrit nulle part. Il est encodé dans cinq lignes de `VTTaleHytalePlugin.setup()` : `HytaleAdapter` en premier pour rattraper les commandes, `TokenModule` avant la construction de `HytaleTokenBinder`. Entre plugins tiers, c'est pire : `VTTALE:vttale=*` garantit que VTTale démarre en premier, mais rien n'ordonne le plugin X par rapport au plugin Y. Si le module de Y a besoin d'un service que X enregistre, `getService` renvoie `null` et le tiers prend une NPE dans son code — le pire endroit pour un bug de framework.

Aujourd'hui `Module` ne déclare ni identité ni dépendance, et `SimpleModuleRegistry.registerModule` active immédiatement, quoi qu'il manque.

Note : le couplage par événements n'est **pas** une dépendance. `DiceRollModule` publie un `SendMessageEvent` que `ChatModule` consomme, mais l'abonnement a lieu à l'activation et la publication à l'exécution — l'ordre d'enregistrement n'a aucun effet. La seule contrainte d'ordre réelle dans cette architecture est *lire un service pendant `onEnable`*.

## Décisions

| Décision | Choix |
|---|---|
| Contrat de dépendance | `Module.requires()` → `Set<Class<?>>` de **services** — dans ce framework, le service est le canal de communication, donc la dépendance réelle est presque toujours un service. Pas d'ids de modules dans `requires()` : un mécanisme, pas deux |
| Identité | `Module.id()` → identifiant stable, clé du dédoublonnage et des logs |
| Cycle de vie d'un id | Réservé à l'**enregistrement**, jamais libéré tant que le registre vit — même si le module est ensuite parqué, refusé par la garde GameSystem, ou en échec d'`onEnable`. Une seule règle, pas de cas particuliers. `disableAll` remet tout à zéro |
| Activation | **Différée réactive** : un module dont les services requis manquent est parqué ; il s'active quand ils apparaissent. Pas de tri topologique — l'ordre émerge au lieu d'être calculé |
| Déclencheur | Après chaque activation réussie **et** à chaque `registerService` |
| Garde anti-réentrance | Armée **autour de `onEnable`**, pas autour de la boucle de drain (voir « Pourquoi la garde » — c'est le point où le design se joue) |
| Désactivation | `disableAll` parcourt les modules en **ordre inverse d'activation** : un dépendant s'éteint avant son fournisseur |
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
     * Services that must be registered before this module can enable, read
     * with {@code kernel.getService(...)} inside {@link #onEnable}.
     * Empty by default. Registration order stops mattering for these: the
     * registry parks the module until every entry resolves.
     * <p>
     * Event coupling is NOT a dependency: subscriptions happen at enable time
     * and publications at runtime, so two modules that talk through events do
     * not constrain each other's order. Declaring one here creates a false
     * constraint, and two modules listening to each other create a deadlock
     * this registry cannot resolve.
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

Un seul chemin d'activation, `tryEnable(module)`, utilisé par les deux entrées (enregistrement direct et drain des parqués) : garde GameSystem, try/catch, `modules.add`. Tout ce qui existe déjà y reste.

État ajouté, tout sous le même moniteur : `pending` (`ArrayList`, ordre d'enregistrement), `ids` (`HashSet`), le compteur `activating` et les drapeaux `draining` / `dirty`.

```java
synchronized registerModule(module):
    if modules.contains(module) or pending.contains(module): return   // inchangé (identité)
    String id = safeId(module);              // id() qui jette ou renvoie null -> ERROR, refus
    if id == null: return
    if !ids.add(id):                         // add() renvoie false si l'id est déjà pris
        ERROR "module id already taken by ..."; return
    Set<Class<?>> missing = unresolved(module);   // requires() qui jette, renvoie null,
    if missing == null: return                    // ou contient null -> ERROR, refus
    if !missing.isEmpty():
        pending.add(module);
        INFO "deferred, waiting for " + missing; return
    tryEnable(module);

synchronized tryEnable(module):
    // garde GameSystem inchangée (refus avant tout effet de bord)
    activating++;                            // <-- LA GARDE COUVRE onEnable, PAS LE DRAIN
    enabled = false
    try { module.onEnable(kernel); enabled = true; }
    catch (Throwable e) { ERROR; }           // id conservé : cf. cycle de vie d'un id
    finally { activating--; }
    if enabled: modules.add(module)
    drainPending()                           // TOUJOURS - voir « Un onEnable en échec draine quand même »

synchronized drainPending():
    if activating > 0 or draining:           // une activation ou un drain est en cours :
        dirty = true; return                 // le drain le plus externe repassera
    draining = true;
    try {
        do {
            dirty = false;
            for (module : List.copyOf(pending)):   // COPIE : tryEnable mute pending
                if (resolves(module)):
                    pending.remove(module);
                    tryEnable(module);
        } while (dirty);                     // les chaînes A -> B -> C se déroulent ici
    } finally { draining = false; }

// appelé par VTTaleKernel.registerService, après le put
void onServiceRegistered():
    drainPending();

synchronized disableAll():
    for (module : modules en ORDRE INVERSE):
        try { module.onDisable(); } catch (Throwable e) { ERROR; }
    modules.clear(); pending.clear(); ids.clear();
```

### Pourquoi la garde anti-réentrance

Chaîne naïve : `registerModule` → `onEnable` → `registerService` → drain → `tryEnable(parqué)` → `onEnable(parqué)`. Tout sur le même thread, pendant que le `onEnable` du premier module n'a pas rendu la main. `TokenModule` illustre le trou :

```java
kernel.registerService(TokenRegistry.class, registry);      // <- un parqué sur TokenRegistry s'activerait ICI
kernel.registerService(BehaviorDispatcher.class, dispatcher);
```

Le dépendant démarrerait dans un monde où `TokenRegistry` existe mais pas `BehaviorDispatcher`, et `modules.add(TokenModule)` n'ayant pas encore eu lieu, la liste recevrait le dépendant avant son fournisseur — ce qui déciderait silencieusement de l'ordre de `disableAll()`. Le verrou réentrant ne protège pas : c'est le même thread, il passe.

**Le drapeau doit couvrir `onEnable`, pas la boucle de drain.** C'est la subtilité de tout le design. Un drapeau posé à l'entrée de `drainPending` ne suffirait pas : dans la chaîne ci-dessus, `tryEnable` appelle `onEnable` **avant** d'appeler `drainPending`, donc au moment de la réentrance aucun drain n'est en cours et un tel drapeau serait à `false` — le drain imbriqué passerait, et le trou serait exactement celui qu'on croit avoir bouché. D'où `activating`, incrémenté autour de `onEnable` lui-même, et un compteur plutôt qu'un booléen puisqu'un `onEnable` peut enregistrer un autre module.

La condition est « aucune activation ni aucun drain en cours → drainer maintenant », pas seulement « reporter au drain externe » : un service peut naître **hors** de tout `onEnable` (le `setup()` plateforme, ou un plugin tiers qui fait `kernel.registerService` sans module). Dans ce cas personne d'autre ne drainerait, et le parqué dormirait jusqu'au prochain `registerModule`.

**Un `onEnable` en échec draine quand même.** Le `catch` ne sort plus avant le drain : un `onEnable` qui enregistre un service puis jette laisse ce service dans le kernel (pas de rollback), et ce service doit réveiller ses dépendants comme n'importe quel autre. Sans lui, le `dirty` couché par le `registerService` imbriqué n'a plus de lecteur : le parqué concerné dort jusqu'à un enregistrement sans rapport — un réveil non déterministe, très pénible à diagnostiquer. Le drain ne réhabilite pas le module en échec : pas de `modules.add`, donc jamais de `onDisable` pour lui.

### Détails d'implémentation qui mordent

- **Itérer sur une copie de `pending`.** `tryEnable` retire du parc, et l'`onEnable` qu'il déclenche peut appeler `registerModule` et y ajouter. Itérer directement sur l'`ArrayList` donne une `ConcurrentModificationException`.
- **`unresolved(module)` doit tolérer plus qu'une exception** : un `requires()` tiers peut renvoyer `null`, ou un `Set` contenant `null` (que `getService(null)` refuserait). Les trois cas sont un refus loggé, pas une remontée d'exception.
- **`modules.contains` et `pending.contains` appellent `equals()`** — code tiers, hors du filet, comme aujourd'hui. Préexistant, non traité ici.

### Verrouillage

- `registerModule`, `tryEnable`, `drainPending`, `disableAll` : moniteur du registre (réentrant — un module qui en enregistre un autre reste correct, comme aujourd'hui).
- `registerService` du kernel appelle désormais `onServiceRegistered` → prend le moniteur du registre. Ce n'est plus un `put` trivial, mais pas d'interblocage : rien n'est appelé en tenant un autre verrou, et c'est du boot-time. `VTTaleKernel` type son champ en `SimpleModuleRegistry` (reste interne au kernel — `getModuleRegistry()` continue d'exposer l'interface).
- `pending`, `ids`, `activating`, `draining`, `dirty` : état simple sous le même moniteur ; `modules` reste `CopyOnWriteArrayList` par prudence vis-à-vis des itérations hors verrou.

## Rapport de fin de démarrage

`SimpleModuleRegistry.reportPendingModules()` (méthode kernel-interne, **pas** dans l'interface `ModuleRegistry`) : logge en ERROR chaque module encore parqué avec les services manquants, puis ne fait plus rien. Appelé par la plateforme en fin de `setup()`, juste avant la vérification du binder token, via un `instanceof` plutôt qu'un cast — un diagnostic ne doit pas pouvoir faire échouer le démarrage si l'implémentation change un jour :

```java
if (kernel.getModuleRegistry() instanceof SimpleModuleRegistry registry) {
    registry.reportPendingModules();
}
```

Le parking courant reste en INFO : bruyant quand tout va bien était l'inverse de ce qu'il faut.

## Ce qui ne change pas

- `HytaleAdapter` reste premier dans `setup()` : il ne fournit aucun service, il ponte les events Hytale vers le bus kernel — les dépendances d'événements sont invisibles à `requires()`. Ça reste une convention plateforme documentée.
- Le binder token et sa vérification `null` : inchangés (mais voir Limites — c'est le seul consommateur réel de `requires()` du dépôt).
- La garde d'exclusivité GameSystem : inchangée, rejouée à l'identique quand un GameSystem arrive via le drain. Deux GameSystems parqués sur le même service → le drain les active dans l'ordre d'enregistrement, le premier publie son service, le second est refusé par la garde — déterministe.
- Le contrat d'échec : un module refusé ou en échec d'`onEnable` ne reçoit jamais `onDisable`. Les parqués n'ont jamais vu `onEnable`, donc jamais `onDisable`.

## Implémentation touchée

| Fichier | Changement |
|---|---|
| `api/.../module/Module.java` | `id()` et `requires()` en default, Javadoc du contrat (dont le non-usage : events) |
| `api/.../module/ModuleRegistry.java` | Javadoc : parc des parqués, refus par id, ordre inverse de désactivation, rapport |
| `api/.../gamesystem/GameSystem.java` | Javadoc d'`id()` : « collisions ever matter » → obligation d'unicité |
| `kernel/.../module/SimpleModuleRegistry.java` | parc `pending`, réserve `ids`, `tryEnable`, `drainPending` avec garde `activating`, `onServiceRegistered`, `reportPendingModules`, `disableAll` en ordre inverse qui vide `modules`/`pending`/`ids` |
| `kernel/.../VTTaleKernel.java` | champ typé `SimpleModuleRegistry` ; `registerService` appelle `onServiceRegistered` |
| `platform/hytale/.../VTTaleHytalePlugin.java` | appel `reportPendingModules()` en fin de `setup()` (via `instanceof`) |
| `docs/architecture.md` | guide auteur de module : déclare `requires()` — l'ordre d'enregistrement n'a plus d'importance **pour les dépendances de service déclarées** ; unicité d'`id()` |

Aucun built-in ne déclare `requires()` : aucun n'a de dépendance de service réelle (vérifié — pas un seul `getService` dans un `onEnable` du dépôt). Aucun override cosmétique d'`id()` non plus — les ids ne servent pas à la résolution, les FQN distincts suffisent.

## Tests (JUnit 5, kernel)

- **Remplace** `distinctInstancesAreNotDeduplicated` (comportement volontairement changé) : deux instances de même classe → même id par défaut → la seconde est refusée, la première tourne. Un `id()` overridé distinct lève le conflit.
- **Met à jour** `disableAllIsIdempotent` : il affirme aujourd'hui `disable:a` puis `disable:b`, l'ordre inverse attend `disable:b` puis `disable:a`.
- **Parc** : module dont le service manque → pas d'`onEnable`, absent de la liste des modules, pas de `onDisable` au shutdown.
- **Inversion d'ordre** : consommateur enregistré avant fournisseur → les deux finissent activés, l'ordre d'activation est fournisseur d'abord.
- **Chaîne** : A requiert S1, B fournit S1 et requiert S2, C fournit S2 — enregistrés dans le désordre, tous activés, ordre respecté.
- **Service hors module** : `kernel.registerService` direct (sans `registerModule`) réveille un parqué — couvre la branche « aucune activation en cours » de la garde.
- **Atomicité du drain — le test qui garde la décision.** Il doit discriminer, donc le consommateur ne requiert **que le premier** service du fournisseur :
    - fournisseur `P` : `onEnable` journalise `P:start`, enregistre S1, enregistre S2, journalise `P:end` ;
    - consommateur `C` : `requires() = {S1}`, `onEnable` journalise `C` ;
    - enregistrer `C` puis `P` ; attendu `["P:start", "P:end", "C"]`.

  Avec la garde mal placée on obtient `["P:start", "C", "P:end"]`. Un consommateur qui requerrait `{S1, S2}` passerait dans les deux cas et ne prouverait rien.
- **Réserve d'id** : un module dont l'`onEnable` échoue garde son id — un second module portant le même id est refusé.
- **Échec après enregistrement** : un `onEnable` qui pose un service puis jette réveille quand même ses dépendants (le service reste posé) ; le module en échec reste absent de la liste et jamais `onDisable`.
- **`requires()` qui jette** → module refusé, rien d'autre n'explose. Idem `id()` qui jette, `requires()` qui renvoie `null`, et `requires()` contenant `null`.
- **Déterminisme GameSystem** : deux GameSystems parqués sur le même service → premier enregistré gagne, second refusé par la garde, jamais activé.
- Les tests GameSystem existants restent valides tels quels.

Le contenu des lignes de log (INFO/ERROR) n'est pas testé : tester les logs, c'est tester l'habillage.

## Limites connues (hors périmètre)

- **La fonctionnalité part sans consommateur intégré.** Tous les tests utiliseront des stubs. Le seul candidat réel du dépôt est `HytaleTokenBinder` : c'est exactement la dépendance que `setup()` traite aujourd'hui à la main (`getService(TokenRegistry.class)` + vérification `null` ligne 47). **Déclencheur** : le jour où le binder devient un `Module` — `requires() = Set.of(TokenRegistry.class)` remplace la vérification manuelle, uniformise son cycle de vie, et donne au mécanisme un premier usage réel plutôt qu'une intention.
- **Le rapport ne couvre que le boot des built-ins.** Un module tiers parqué après le rapport (plugin chargé après VTTale) n'a que sa ligne INFO. Il n'existe pas de moment universel « tous les plugins sont là » côté kernel. **Déclencheur** : le pont Hytale s'abonne à un event server-ready et relance `reportPendingModules()` — une ligne, quand un cas réel le demande.
- **Collision FQN résiduelle** : deux JARs tiers chargés par des classloaders différents peuvent porter le même FQN. Remède documenté : override namespacé d'`id()`. Le default est meilleur que `getSimpleName()`, pas parfait.
- **`requires()` ne voit que les services** : les dépendances d'événements (HytaleAdapter d'abord) restent une convention plateforme.
- **Les services ne sont jamais désenregistrés.** `disableAll` remet le registre à zéro mais la map de services du kernel survit : après un `disableAll`, `getService(GameSystem.class)` renvoie toujours le système désactivé. Sans rechargement c'est sans effet ; à reprendre avec le travail sur le cycle de vie.
- Pas d'API de consultation des parqués au-delà du rapport, pas de désactivation individuelle, pas d'`onServiceRegistered` dans l'interface publique. À ajouter quand un cas réel le demande.