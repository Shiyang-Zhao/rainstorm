package com.streamprocessingsystem.util;

import java.util.EnumSet;

public enum Command {

    // Membership commands
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
    EXIT,

    // File System commands
    CREATE,
    GET,
    GET_ACK,
    APPEND,
    MERGE,
    MERGE_ACK,
    LS,
    STORE,
    GETFROMREPLICA,
    GETFROMREPLICA_ACK,
    MULTIAPPEND,
    JOIN_REPLICATE,
    FAIL_REPLICATE,
    REPLICATE,

    // Stream Processing System commands
    RAINSTORM,
    OP1,
    OP1_ACK,
    OP2,
    OP2_ACK;
    
    private static final EnumSet<Command> MP2_COMMANDS = EnumSet.of(
            JOIN, LEAVE, PING, ACK, LIST_MEM, LIST_SELF, ENABLE_SUS, DISABLE_SUS, STATUS_SUS, SUSPECTED, FAILED, EXIT);

    private static final EnumSet<Command> MP3_COMMANDS = EnumSet.of(
            CREATE, GET, GET_ACK, APPEND, MERGE, MERGE_ACK,
            LS, STORE, GETFROMREPLICA, GETFROMREPLICA_ACK, MULTIAPPEND,
            JOIN_REPLICATE,
            FAIL_REPLICATE, REPLICATE);

    private static final EnumSet<Command> MP4_COMMANDS = EnumSet.of(RAINSTORM, OP1, OP1_ACK, OP2, OP2_ACK);

    public static boolean isMP2Command(Command command) {
        return MP2_COMMANDS.contains(command);
    }

    public static boolean isMP3Command(Command command) {
        return MP3_COMMANDS.contains(command);
    }

    public static boolean isMP4Command(Command command) {
        return MP4_COMMANDS.contains(command);
    }

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
            System.out.println("Invalid command: " + command);
            return null;
        }
    }
}
