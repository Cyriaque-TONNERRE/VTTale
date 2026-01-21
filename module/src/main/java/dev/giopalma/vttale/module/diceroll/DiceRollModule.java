package dev.giopalma.vttale.module.diceroll;

import dev.giopalma.vttale.api.Kernel;
import dev.giopalma.vttale.api.events.CommandExecutedEvent;
import dev.giopalma.vttale.api.events.EventBus;
import dev.giopalma.vttale.api.events.EventContext;
import dev.giopalma.vttale.module.chat.SendMessageEvent;
import dev.giopalma.vttale.api.module.Module;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Module responsible for handling dice roll commands.
 */
public class DiceRollModule implements Module {
    private static final Pattern DICE_PATTERN = Pattern.compile("^(\\d+)?d(\\d+)(?:([+-])(\\d+))?$");
    private final Random random = new Random();
    private EventBus eventBus;

    @Override
    public void onEnable(Kernel kernel) {
        this.eventBus = kernel.getEventBus();
        kernel.getCommandRegistry().registerCommand("roll", "Roll dice");
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

        // Join arguments and remove spaces to handle notations like "1d10 + 10"
        String notation = String.join("", args).replace(" ", "").toLowerCase();
        Matcher matcher = DICE_PATTERN.matcher(notation);

        if (!matcher.matches()) {
            reply(context, "Invalid dice notation: " + notation);
            return;
        }

        try {
            int count = matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
            int sides = Integer.parseInt(matcher.group(2));
            String operator = matcher.group(3);
            int modifier = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));

            if (count > 100 || sides > 1000) {
                reply(context, "Dice count or sides too high!");
                return;
            }

            if (sides <= 0) {
                reply(context, "Dice sides must be greater than 0!");
                return;
            }

            int total = 0;
            StringBuilder rolls = new StringBuilder();
            for (int i = 0; i < count; i++) {
                int roll = random.nextInt(sides) + 1;
                total += roll;
                rolls.append(roll).append(i < count - 1 ? ", " : "");
            }

            if ("+".equals(operator)) {
                total += modifier;
            } else if ("-".equals(operator)) {
                total -= modifier;
            }

            String resultMsg = String.format("Rolled %s: [%s]%s = %d",
                    notation, rolls, (operator != null ? " " + operator + modifier : ""), total);

            reply(context, resultMsg);

        } catch (NumberFormatException e) {
            reply(context, "Error parsing numbers in notation.");
        }
    }

    private void reply(EventContext context, String message) {
        eventBus.publish(new SendMessageEvent(message, context.getSenderId()), context);
    }
}
