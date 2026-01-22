package org.vttale.vttale.platform.hytale;

import java.util.UUID;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.events.RegisterCommandRequest;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.module.chat.PlatformBroadcastEvent;

public class HytaleAdapter implements Module {

    private JavaPlugin plugin;

    public HytaleAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable(Kernel kernel) {
        // Subscribe to future command registrations
        kernel.getEventBus().subscribe(RegisterCommandRequest.class, (event, context) -> {
            registerHytaleCommand(event);
        });

        // Catch up on commands that were registered before this adapter was enabled
        kernel.getCommandRegistry().getRegisteredCommands().values().forEach(this::registerHytaleCommand);

        kernel.getEventBus().subscribe(PlatformBroadcastEvent.class, (event, context) -> {
            String targetId = event.getTargetId();

            if ("CONSOLE".equals(targetId)) {
                // Command was executed from console, print to server console
                plugin.getLogger().atInfo().log(event.getFormattedMessage());
            } else {
                // Command was executed by a player, send message to that player
                PlayerRef player = Universe.get().getPlayer(UUID.fromString(targetId));
                if (player != null) {
                    player.sendMessage(Message.raw(event.getFormattedMessage()));
                }
            }
        });
    }

    /**
     * Registers a command with the Hytale command registry.
     */
    private void registerHytaleCommand(RegisterCommandRequest request) {
        plugin.getCommandRegistry().registerCommand(new Command(request));
    }
}
