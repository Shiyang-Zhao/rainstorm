package com.membership.util;

public enum Command {
    JOIN,
    LEAVE,
    PING,
    ACK,
    LIST_MEM,
    LIST_SELF,
    ENABLE_SUS,
    DISABLE_SUS,
    STATUS_SUS,
    SUSPECTED,
    FAILED,
    EXIT;

    /**
     * Converts a string input to a Command enum constant in a safe manner.
     * If the input is invalid, prints an error message to the user.
     *
     * @param command the string representation of the command
     * @return an Optional containing the Command if found, or empty if invalid
     */
    public static Command getCommand(String command) {
        try {
            return Command.valueOf(command.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            System.out.println("Invalid command: " + command + ". Available commands: join, leave, list_mem, list_self, enable_sus, disable_sus, status_sus, exit");
            return null;
        }
    }
}
