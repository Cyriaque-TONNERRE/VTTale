package org.vttale.vttale.module.diceroll;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.events.CommandExecutedEvent;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.module.chat.SendMessageEvent;

import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Module responsible for handling dice roll commands.
 * Rolls are also published as {@link DiceRolledEvent} so other modules can react.
 */
public class DiceRollModule implements Module {

    private static final Pattern DICE_SHAPE = Pattern.compile("(\\d+)?d(\\d+)");

    // ponytail: caps block chat-input abuse (each die is cheap, a million dice is not)
    private static final int MAX_DICE = 100;
    private static final int MAX_SIDES = 1000;

    private final Dice dice = new Dice(RandomGenerator.getDefault());
    private EventBus eventBus;

    @Override
    public void onEnable(Kernel kernel) {
        this.eventBus = kernel.getEventBus();
        kernel.getCommandRegistry().registerCommand("roll", "Roll dice, e.g. /roll 2d6+3");
        eventBus.subscribe(CommandExecutedEvent.class, this::onCommandExecuted);
    }

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
        String notation = String.join("", args).replace(" ", "").toLowerCase();

        try {
            // Validate shape and caps BEFORE rolling: a hostile "999999999d6" must
            // not allocate anything.
            Matcher shape = DICE_SHAPE.matcher(notation);
            int count = 1;
            int sides = 0;
            if (shape.find()) {
                count = shape.group(1) == null ? 1 : Integer.parseInt(shape.group(1));
                sides = Integer.parseInt(shape.group(2));
            }
            if (sides == 0) {
                reply(context, "Invalid dice notation: " + notation);
                return;
            }
            if (count > MAX_DICE || sides > MAX_SIDES) {
                reply(context, "Dice count or sides too high!");
                return;
            }

            DiceRolledEvent result = dice.roll(notation);
            eventBus.publish(result, context);
            reply(context, format(result));
        } catch (IllegalArgumentException e) {
            reply(context, "Invalid dice notation: " + notation);
        }
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
