package org.vttale.vttale.module.diceroll;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.events.CommandExecutedEvent;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.module.chat.SendMessageEvent;

import java.util.stream.Collectors;

/**
 * Module exposing dice rolling as a service ({@link DiceService}) plus the
 * /roll command as a thin notation parser on top of it. Other modules roll
 * via {@code kernel.getService(DiceService.class)} — not much should flow
 * through commands.
 */
public class DiceRollModule implements Module {

    // ponytail: caps block chat-input abuse (each die is cheap, a million dice is not)
    private static final int MAX_DICE = 100;
    private static final int MAX_SIDES = 1000;

    private EventBus eventBus;
    private DiceService dice;

    @Override
    public void onEnable(Kernel kernel) {
        this.eventBus = kernel.getEventBus();
        this.dice = new SimpleDiceService(eventBus);
        kernel.registerService(DiceService.class, dice);
        kernel.getCommandRegistry().registerCommand("roll", "Roll dice, e.g. /roll 2d6+3");
        eventBus.subscribe(CommandExecutedEvent.class, this::onCommandExecuted);
    }

    /** Command layer: parse the notation, enforce the caps, delegate to the service. */
    private void onCommandExecuted(CommandExecutedEvent event, EventContext context) {
        if (!event.getCommandName().equals("roll")) {
            return;
        }

        String[] args = event.getArgs();
        if (args.length == 0) {
            reply(context, "Usage: /roll <dice_notation>. Example: /roll 2d6+3");
            return;
        }

        // Join arguments and strip spaces to handle notations like "1d10 + 10"
        String notation = String.join("", args).replace(" ", "");

        try {
            ParsedRoll roll = parse(notation);
            if (roll.count() > MAX_DICE || roll.sides() > MAX_SIDES) {
                reply(context, "Dice count or sides too high!");
                return;
            }
            DiceRolledEvent result = dice.roll(roll.count(), roll.sides(), roll.modifier());
            reply(context, format(result));
        } catch (IllegalArgumentException e) {
            reply(context, "Invalid dice notation: " + notation);
        }
    }

    /** A parsed notation: {@code count} dice of {@code sides} faces, plus {@code modifier}. */
    private record ParsedRoll(int count, int sides, int modifier) {}

    /**
     * Parses "NdM[+/-K]"; "d6" and "6" both mean 1d6. Throws
     * {@link NumberFormatException} (an {@link IllegalArgumentException}) on
     * garbage or absurd numbers, before any allocation.
     */
    private static ParsedRoll parse(String notation) {
        String expr = notation.strip().toLowerCase();
        int modifier = 0;
        int plus = expr.indexOf('+');
        int minus = expr.indexOf('-');
        if (plus >= 0) {
            modifier = Integer.parseInt(expr.substring(plus + 1).strip());
            expr = expr.substring(0, plus).strip();
        } else if (minus >= 0) {
            modifier = -Integer.parseInt(expr.substring(minus + 1).strip());
            expr = expr.substring(0, minus).strip();
        }

        int count = 1;
        int d = expr.indexOf('d');
        if (d >= 0) {
            String before = expr.substring(0, d).strip();
            if (!before.isEmpty()) {
                count = Integer.parseInt(before);
            }
            expr = expr.substring(d + 1).strip();
        }
        int sides = Integer.parseInt(expr);
        return new ParsedRoll(count, sides, modifier);
    }

    private static String format(DiceRolledEvent r) {
        String rolls = r.rolls().stream().map(String::valueOf).collect(Collectors.joining(", "));
        String mod = r.modifier() == 0 ? ""
                : (r.modifier() > 0 ? " + " + r.modifier() : " - " + (-r.modifier()));
        // ASCII only: the game chat font does not render glyphs like emoji/arrows
        return r.notation() + " -> [" + rolls + "]" + mod + " = " + r.total();
    }

    private void reply(EventContext context, String message) {
        eventBus.publish(new SendMessageEvent(message, context.getSenderId()), context);
    }
}
