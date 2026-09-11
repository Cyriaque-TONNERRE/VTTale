# Design — Module clone : créer une figurine à l'image d'un joueur

Date : 2026-09-11
Statut : validé (design approuvé)
Branche : `test/player-token`

## Contexte

Pour le jeu de rôle sur table, le MJ et les joueurs ont besoin de
**figurines** : une entité posée sur le plateau qui représente un personnage,
sans vie propre, déplaçable via le système de tokens. Premier cas d'usage : le
clone d'un joueur — une entité humanoïde qui porte le **skin** du joueur
source, spawnée à sa position.

Le serveur Hytale fournit tout le nécessaire (vérifié dans les sources 0.6.x,
voir aussi `docs/architecture.md`) :

- Le skin d'un joueur connecté est un composant ECS,
  `PlayerSkinComponent` (posé à la connexion depuis `auth.getSkin()`).
- `EntityTrackerSystems.EntitySkin` envoie le packet `PlayerSkinUpdate` à tous
  les viewers de **toute** entité portant `Visible + PlayerSkinComponent` — le
  rendu réseau est automatique, joueur ou non.
- `NPCPlugin.spawnEntity(store, roleIndex, position, rotation, model, postSpawn)`
  (public) spawn un NPC ; `CosmeticsModule.createModel(skin)` construit le
  modèle humanoïde correspondant au skin ; le callback `postSpawn` pose le
  `PlayerSkinComponent` sur l'entité. C'est exactement la recette de la
  commande vanilla `/npc spawn -randomModel` (`NPCSpawnCommand.java:264-272`),
  en remplaçant le skin aléatoire par celui du joueur source.

Le code qui touche `com.hypixel.*` reste confiné à `platform/hytale` (règle du
dépôt) : la logique de plateau (commandes, règle « 1 clone max », tokens) vit
dans un module pur testable, et le spawn est exposé par un service implémenté
par le platform — le même découpage que `DiceService`.

## Décisions

| Décision | Choix |
|---|---|
| Déclencheur | `/clone [joueur]` : sans argument, chaque joueur clone son propre personnage ; avec argument, le clone d'un autre joueur en ligne |
| Comportement | Figurine statique : le clone apparaît au modèle du joueur puis vit sa vie, dissocié ; il se déplace ensuite via le système de tokens (`TokenUpdatedEvent`) |
| Unicité | **Un seul clone par joueur source** : si un clone existe déjà (requête registry : owner + tag `clone`), `/clone` est un no-op avec message |
| Suppression | `/unclone [joueur]` : despawn de l'entité (`despawnEntityForToken`, existant) + `TokenRegistry.remove(token)` |
| Source de vérité | Le token, pas l'entité : l'entité n'est que l'avatar visuel du token |
| Ordre spawn/token | Le service spawn **d'abord** ; le token n'est créé que si le spawn réussit — pas de token orphelin |
| Cloisonnement | Interface `PlayerCloneService` définie dans `module/clone`, implémentée par `HytaleTokenBinder` (platform). `PlayerCloneModule.requires() = {TokenRegistry, PlayerCloneService}` : le registry park le module jusqu'à l'enable du binder, l'ordre de déclaration dans `setup()` ne compte pas |

## Le flow

```
/clone Bob
  └─ Command (platform) publie CommandExecutedEvent (senderId = UUID | "CONSOLE")
      └─ PlayerCloneModule (pur)
          ├─ cible : arg ? resolvePlayer(name) : senderId
          ├─ règle 1-max : query registry (owner + tag "clone") → no-op + message si trouvé
          └─ service.spawnClone(uuidBob)
              └─ HytaleTokenBinder (platform), dans world.execute() :
                  ├─ lit PlayerSkinComponent de Bob
                  ├─ NPCPlugin.spawnEntity(roleIndex, position de Bob,
                  │                        CosmeticsModule.createModel(skin),
                  │                        postSpawn → putComponent(PlayerSkinComponent))
                  └─ retourne l'UUID de l'entité (CompletableFuture)
          └─ module : tokenRegistry.create("Bob (clone)", PLAYER_CHARACTER, owner=Bob)
                      + tag "clone" + tokenRegistry.bindToEntity(tokenId, entityId)
                      → TokenBoundEvent → synchro existante du binder
```

Pas de sync position initiale : l'entité spawn déjà à la place du joueur ; le
token est bindé juste après, les déplacements ultérieurs passent par la
synchro `TokenUpdatedEvent` déjà écrite.

## API du service

```java
public interface PlayerCloneService {

    /**
     * Spawns a humanoid NPC wearing the skin of the source player, at the
     * player's current position. Does NOT create or bind any token.
     *
     * @param sourcePlayerUuid the player whose skin and position to clone
     * @return the spawned entity's UUID
     */
    CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid);

    /**
     * Resolves an online player's name to its UUID.
     *
     * @return the UUID, or null if unknown or offline
     */
    UUID resolvePlayer(String playerName);
}
```

Le despawn ne passe pas par ce service : `HytaleTokenBinder.despawnEntityForToken(token)`
existe déjà et couvre `/unclone`.

## Fichiers

| Fichier | Rôle |
|---|---|
| `module/clone/PlayerCloneService.java` (nouveau) | L'interface ci-dessus |
| `module/clone/PlayerCloneModule.java` (nouveau) | Module pur : commandes `/clone`/`/unclone`, règle 1-max, cycle de vie du token, messages. `id() = "vttale:clone"` |
| `platform/hytale/.../HytaleTokenBinder.java` | `implements PlayerCloneService`, `kernel.registerService(PlayerCloneService.class, this)` dans `onEnable` ; implémente le spawn (recette mémoire skin) |
| `platform/hytale/.../VTTaleHytalePlugin.java` | `registerModule(new PlayerCloneModule())` |
| `module/src/test/.../PlayerCloneModuleTest.java` (nouveau) | Tests du module pur avec services fake |

## Gestion d'erreurs

| Cas | Comportement |
|---|---|
| `/clone` sans argument en console | Erreur : spécifier un joueur |
| Pseudo inconnu ou hors ligne (`resolvePlayer` → null) | Erreur |
| Clone déjà existant pour ce joueur | No-op + message (règle 1-max) |
| Joueur sans `PlayerSkinComponent` | Erreur (ne devrait pas arriver) |
| Échec du spawn (future exceptionnel) | Erreur, **rien n'est créé** |
| `/unclone` sans clone existant | Message, rien à faire |

Toutes les réponses passent par `SendMessageEvent` (pattern dice). Le bus est
synchronisé : pas de course entre le check 1-max et la création du token.

## Point ouvert assumé : le rôle humanoïde

`NPCPlugin.spawnEntity` exige un `roleIndex` résolu depuis les assets
`Server/NPC/Roles`, dont les noms ne sont pas connus hors jeu. Le binder porte
une **constante unique** `NPC_ROLE_NAME` et logue au boot les rôles disponibles
(`getRoleTemplateNames(true)`) ; après premier test en jeu, on y met le bon nom.
Tant qu'il est faux, `/clone` échoue proprement (branche « échec du spawn »).

## Tests (module pur, JUnit 5)

- `/clone` crée un token au nom `"<Joueur> (clone)"`, type `PLAYER_CHARACTER`,
  owner = joueur source, tag `clone`, et bind l'entité retournée.
- Règle 1-max : un second `/clone` pour le même joueur = no-op + message, pas
  de token, pas d'appel au service.
- `/clone Bob` avec `resolvePlayer("Bob") → null` : erreur.
- Échec du spawn (future exceptionnel) : erreur, aucun token créé.
- `/unclone` : despawn du token (service + remove) ; sans clone existant :
  message, pas d'erreur.
- `/clone` sans argument en console (`senderId = "CONSOLE"`) : erreur.

La partie platform (spawn, skin) n'est pas unit-testable (SDK Hytale requis,
hors CI) — validation en jeu : `/clone`, la figurine porte le skin du joueur,
`/unclone` la retire.
