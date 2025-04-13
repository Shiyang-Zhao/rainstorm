package com.logquery.server;

import org.unix4j.Unix4j;
import org.unix4j.unix.Grep;
import org.unix4j.unix.grep.GrepOptionSet_Fcilnvx;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GrepAPI {
    private final String command;
    private GrepOptionSet_Fcilnvx options;
    private String pattern;
    private final Path logDirectory;

    public GrepAPI(String command, Path logDirectory) {
        this.command = command.trim().replaceAll("\\s+", " ");
        this.logDirectory = logDirectory;
    }

    public GrepOptionSet_Fcilnvx getOptions() {
        return options;
    }

    /**
     * Retrieves the list of log files from the specified log directory that match
     * the regex pattern.
     * Only files with names like "vmX.log" are included.
     *
     * @return List of filenames that match the pattern.
     * @throws IOException If an I/O error occurs while reading the log directory.
     */
    public List<String> getFiles() throws IOException {
        String regex = "vm\\d+\\.log";
        Pattern pattern = Pattern.compile(regex);
        List<String> fileList = new ArrayList<>();

        Files.list(logDirectory)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    String fileName = file.getFileName().toString();
                    Matcher matcher = pattern.matcher(fileName);
                    if (matcher.matches()) {
                        fileList.add(fileName);
                    }
                });
        return fileList;
    }

    /**
     * Sets the grep options based on the flags provided in the command.
     * Each flag corresponds to a different grep option.
     *
     * @param flags The string of flags (e.g., "ci" for case-insensitive and line
     *              numbers).
     */
    private void setGrepOptions(String flags) {
        options = null;
        for (char option : flags.toCharArray()) {
            switch (option) {
                case 'c':
                    if (options == null) {
                        options = Grep.Options.c;
                    } else {
                        options = options.c;
                    }
                    break;
                case 'F':
                    if (options == null) {
                        options = Grep.Options.F;
                    } else {
                        options = options.F;
                    }
                    break;
                case 'i':
                    if (options == null) {
                        options = Grep.Options.i;
                    } else {
                        options = options.i;
                    }
                    break;
                case 'v':
                    if (options == null) {
                        options = Grep.Options.v;
                    } else {
                        options = options.v;
                    }
                    break;
                case 'n':
                    if (options == null) {
                        options = Grep.Options.n;
                    } else {
                        options = options.n;
                    }
                    break;
                case 'l':
                    if (options == null) {
                        options = Grep.Options.l;
                    } else {
                        options = options.l;
                    }
                    break;
                case 'x':
                    if (options == null) {
                        options = Grep.Options.x;
                    } else {
                        options = options.x;
                    }
                    break;
                default:
                    System.out.println("Unknown option: " + option);
            }
        }
    }

    /**
     * Executes the grep command on the log files in the specified directory. It
     * processes the command,
     * sets the grep options, searches through the files, and returns the results.
     *
     * @return The result of the grep command, including matched lines and the total
     *         count.
     */
    public String executeCommand() {
        try {
            // Split the command into components (grep, flags, pattern)
            String[] commandComponent = command.trim().split("\\s+");
            if (!commandComponent[0].equals("grep") || commandComponent.length < 2) {
                return "Invalid command format. Usage: grep [options] pattern";
            }

            String flags = "";
            StringBuilder patternBuilder = new StringBuilder();

            boolean isPatternStarted = false;
            // Parse flags and pattern from the command
            for (int i = 1; i < commandComponent.length; i++) {
                if (!isPatternStarted && commandComponent[i].startsWith("-")) {
                    flags += commandComponent[i].substring(1);
                } else {
                    if (patternBuilder.length() > 0) {
                        patternBuilder.append(" ");
                    }
                    patternBuilder.append(commandComponent[i]);
                    isPatternStarted = true;
                }
            }

            pattern = patternBuilder.toString();
            if (pattern.isEmpty()) {
                return "Invalid command format. Usage: grep [options] pattern";
            }

            setGrepOptions(flags);

            List<String> files = getFiles();
            if (files.isEmpty()) {
                return "No log files found in directory.";
            }

            int totalLines = 0;
            StringBuilder resultBuilder = new StringBuilder();

            for (String fileName : files) {
                File file = logDirectory.resolve(fileName).toFile();
                String result = options == null
                        ? Unix4j.grep(pattern, file).toStringResult()
                        : Unix4j.grep(options, pattern, file).toStringResult();

                if (flags.contains("c")) {
                    int lineCount = result.isEmpty() ? 0 : Integer.parseInt(result.trim());
                    totalLines += lineCount;
                    String summary = "Matched line count for file " + fileName + ": " + lineCount + "\n";
                    resultBuilder.insert(0, summary);
                } else {
                    if (!result.isEmpty()) {
                        resultBuilder.append("\n").append("Matched lines for file: ").append(fileName).append("\n")
                                .append(result).append("\n");
                    }
                }
            }
            if (flags.contains("c")) {
                resultBuilder.insert(0, "Total matched line count: " + totalLines + "\n");
            }
            return resultBuilder.toString();
        } catch (IOException e) {
            return "Error while accessing files: " + e.getMessage();
        } catch (Exception e) {
            return "Error while executing grep: " + e.getMessage();
        }
    }
}
