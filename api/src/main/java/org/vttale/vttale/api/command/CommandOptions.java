package org.vttale.vttale.api.command;

/**
 * Immutable configuration options for VTT commands.
 * <p>
 * Use the {@link Builder} to create instances with custom options.
 * </p>
 *
 * @see CommandRegistry#registerCommand(String, String, CommandOptions)
 */
public final class CommandOptions {
    private final boolean playerOnly;
    private final String permission;
    private final boolean hidden;

    private CommandOptions(Builder builder) {
        this.playerOnly = builder.playerOnly;
        this.permission = builder.permission;
        this.hidden = builder.hidden;
    }

    /**
     * Returns a new builder for creating command options.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns default command options.
     *
     * @return default options (not player-only, no permission, not hidden)
     */
    public static CommandOptions defaults() {
        return new Builder().build();
    }

    /**
     * Returns whether this command is restricted to players only.
     *
     * @return true if player-only
     */
    public boolean isPlayerOnly() {
        return playerOnly;
    }

    /**
     * Returns the permission required to execute this command.
     *
     * @return the permission string, or null if no permission is required
     */
    public String getPermission() {
        return permission;
    }

    /**
     * Returns whether this command is hidden from help listings.
     *
     * @return true if hidden
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Builder for creating {@link CommandOptions} instances.
     */
    public static class Builder {
        private boolean playerOnly = false;
        private String permission = null;
        private boolean hidden = false;

        private Builder() {
        }

        /**
         * Sets whether the command is restricted to players only.
         *
         * @param playerOnly true to restrict to players
         * @return this builder
         */
        public Builder playerOnly(boolean playerOnly) {
            this.playerOnly = playerOnly;
            return this;
        }

        /**
         * Sets the permission required to execute the command.
         *
         * @param permission the permission string
         * @return this builder
         */
        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        /**
         * Sets whether the command is hidden from help listings.
         *
         * @param hidden true to hide
         * @return this builder
         */
        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        /**
         * Builds the command options.
         *
         * @return a new CommandOptions instance
         */
        public CommandOptions build() {
            return new CommandOptions(this);
        }
    }
}
