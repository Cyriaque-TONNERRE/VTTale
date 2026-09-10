package org.vttale.vttale.module.token.behavior;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.token.CoreTokenType;
import org.vttale.vttale.api.token.Token;
import org.vttale.vttale.api.token.behavior.Behavior;
import org.vttale.vttale.api.token.behavior.BehaviorContext;
import org.vttale.vttale.api.token.behavior.BehaviorDispatcher;
import org.vttale.vttale.api.token.behavior.BehaviorEvent;
import org.vttale.vttale.kernel.VTTaleKernel;
import org.vttale.vttale.module.token.SimpleTokenRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class SimpleBehaviorDispatcherTest {

    /** A behaviour that logs every event it sees, prefixed with the token it is attached to. */
    private static final class Listening implements Behavior {
        private final String id;
        private final List<String> log;
        private final boolean explode;
        Listening(String id, List<String> log) { this(id, log, false); }
        Listening(String id, List<String> log, boolean explode) {
            this.id = id; this.log = log; this.explode = explode;
        }
        @Override public String getId() { return id; }
        @Override public void onEvent(Token token, BehaviorEvent event, BehaviorContext context) {
            log.add(token.getName() + "/" + id);
            if (explode) {
                throw new IllegalStateException("boom");
            }
        }
    }

    private record Attack(Token source, Token target) implements BehaviorEvent {
        @Override public Optional<Token> getSourceToken() { return Optional.ofNullable(source); }
        @Override public Optional<Token> getTargetToken() { return Optional.ofNullable(target); }
    }

    private SimpleTokenRegistry registry;
    private BehaviorDispatcher dispatcher;
    private List<String> log;
    private List<BehaviorEvent> globalSeen;

    @BeforeEach
    void setUp() {
        registry = new SimpleTokenRegistry(new VTTaleKernel());
        dispatcher = new SimpleBehaviorDispatcher(registry);
        log = new ArrayList<>();
        globalSeen = new ArrayList<>();
    }

    private Token tokenWith(String name, String... behaviorIds) {
        Token token = registry.create(name, CoreTokenType.MONSTER);
        for (String id : behaviorIds) {
            token.attachBehavior(new Listening(id, log));
        }
        return token;
    }

    @Test
    @DisplayName("dispatch reaches both the source and the target token")
    void dispatchReachesBothEnds() {
        Token attacker = tokenWith("Attacker", "a");
        Token defender = tokenWith("Defender", "d");

        dispatcher.dispatch(new Attack(attacker, defender));

        // The target is served first, then the source.
        assertIterableEquals(List.of("Defender/d", "Attacker/a"), log);
    }

    @Test
    @DisplayName("a token that is both source and target is only served once")
    void selfTargetingIsNotDoubled() {
        Token token = tokenWith("Solo", "a");

        dispatcher.dispatch(new Attack(token, token));

        assertIterableEquals(List.of("Solo/a"), log);
    }

    @Test
    @DisplayName("broadcast reaches every token in the registry")
    void broadcast() {
        tokenWith("A", "x");
        tokenWith("B", "x");
        tokenWith("C");

        dispatcher.broadcast(new Attack(null, null));

        assertEquals(2, log.size(), "only tokens carrying a behaviour react");
    }

    @Test
    @DisplayName("dispatchTo targets an explicit token or collection")
    void explicitTargets() {
        Token a = tokenWith("A", "x");
        Token b = tokenWith("B", "x");

        dispatcher.dispatchTo(new Attack(null, null), a);
        assertIterableEquals(List.of("A/x"), log);

        log.clear();
        dispatcher.dispatchTo(new Attack(null, null), List.of(a, b));
        assertIterableEquals(List.of("A/x", "B/x"), log);
    }

    @Test
    @DisplayName("a behaviour that throws does not stop the others")
    void behaviourFailureIsContained() {
        Token token = registry.create("Solo", CoreTokenType.MONSTER);
        token.attachBehavior(new Listening("boom", log, true));
        token.attachBehavior(new Listening("ok", log));

        assertDoesNotThrow(() -> dispatcher.dispatchTo(new Attack(null, null), token));
        assertEquals(2, log.size(), "both behaviours must have been invoked");
    }

    @Test
    @DisplayName("global listeners see every dispatch exactly once and can be removed")
    void globalListeners() {
        Token token = tokenWith("A", "x");
        BehaviorDispatcher.BehaviorEventListener listener = globalSeen::add;
        dispatcher.addGlobalListener(listener);

        dispatcher.dispatch(new Attack(null, token));
        assertEquals(1, globalSeen.size());

        dispatcher.broadcast(new Attack(null, null));
        assertEquals(2, globalSeen.size());

        dispatcher.removeGlobalListener(listener);
        dispatcher.broadcast(new Attack(null, null));
        assertEquals(2, globalSeen.size(), "a removed listener must go silent");
    }

    @Test
    @DisplayName("a failing global listener does not break the dispatch")
    void globalListenerFailureIsContained() {
        dispatcher.addGlobalListener(event -> { throw new IllegalStateException("boom"); });
        dispatcher.addGlobalListener(globalSeen::add);

        assertDoesNotThrow(() -> dispatcher.broadcast(new Attack(null, null)));
        assertEquals(1, globalSeen.size());
    }
}
