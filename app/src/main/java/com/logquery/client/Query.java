package com.logquery.client;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class Query {
    private Connection connection;

    public Query(Connection connection) {
        this.connection = connection;
    }

    /**
     * Sends the specified query to all available hosts. This is done concurrently
     * using an ExecutorService,
     * which submits tasks to send the query to each host via its respective
     * DataOutputStream.
     *
     * @param query The query string to be sent to all hosts.
     */
    public void sendQueryToAll(String query) {
        ConcurrentHashMap<String, DataOutputStream> outputs = connection.getOutputs();
        ExecutorService executorService = connection.getExecutorService();

        for (String host : outputs.keySet()) {
            executorService.submit(() -> {
                try {
                    outputs.get(host).writeUTF(query);
                    outputs.get(host).flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    /**
     * Receives the responses from all successful connections concurrently. The
     * responses are read from the
     * DataInputStreams, processed, and then written to log files for each host.
     * Additionally, the total line
     * count of matched results is calculated and printed.
     */
    public void receiveResponses(String queryContent) {
        ExecutorService executorService = connection.getExecutorService();
        ConcurrentHashMap<String, DataInputStream> inputs = connection.getInputs();
        String[] hosts = connection.getHosts();

        List<Future<Integer>> futures = new ArrayList<>();
        String flags = extractFlags(queryContent);
        boolean shouldCalculateLineCount = flags.contains("c");

        // Loop through all hosts and receive responses from those with successful
        // connections
        for (int i = 0; i < hosts.length; i++) {
            String host = hosts[i];
            int vmId = i + 1;

            if (connection.getSuccessfulConnections().contains(host)) {
                Future<Integer> future = executorService.submit(() -> {
                    int totalLineCount = 0;
                    try {
                        // Read the response bytes and convert to string
                        int bytesLength = inputs.get(host).readInt();
                        byte[] bytesArray = new byte[bytesLength];
                        inputs.get(host).readFully(bytesArray);
                        String grepCommandResult = new String(bytesArray, "UTF-8");

                        // Prepare header for the response
                        String header = "[" + java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) +
                                "] Response from VM " + vmId + " (" + host + "):\n";

                        String fileName = "log_server_" + vmId + "_" + host + ".txt";
                        String separator = "\n===========================================================================================\n";

                        if (shouldCalculateLineCount) {
                            // Extract line count from the response
                            String firstLine = grepCommandResult.split("\n", 2)[0];
                            totalLineCount = Integer.parseInt(firstLine.split(":")[1].trim());
                            System.out.println(header + grepCommandResult.split("\n\n", 2)[0]);
                        } else {
                            writeToLogFile(fileName, separator + header + grepCommandResult + separator);
                            System.out.println("Output is written to " + fileName);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return totalLineCount;
                });
                futures.add(future);
            }
        }

        int totalAllVMLineCount = 0;
        for (Future<Integer> future : futures) {
            try {
                int lineCount = future.get();
                if (shouldCalculateLineCount) {
                    totalAllVMLineCount += lineCount;
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        if (shouldCalculateLineCount) {
            System.out.println("\nTotal matched line count for all VMs: " + totalAllVMLineCount);
        }
    }

    /**
     * Writes the specified content to a log file. Each log file is named uniquely
     * based on the VM ID and host IP.
     *
     * @param fileName The name of the log file.
     * @param content  The content to be written to the log file.
     */
    private void writeToLogFile(String fileName, String content) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            writer.write(content);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Extracts the flags from a grep command. The flags are the substring
     * starting from the first '-' until the first non-flag argument.
     *
     * @param command The grep command to extract flags from.
     * @return The flags extracted from the command.
     */
    private String extractFlags(String command) {
        StringBuilder flags = new StringBuilder();
        String[] commandComponents = command.trim().split("\\s+");
        for (int i = 1; i < commandComponents.length; i++) {
            if (commandComponents[i].startsWith("-")) {
                flags.append(commandComponents[i].substring(1));
            } else {
                break;
            }
        }
        return flags.toString();
    }
}
