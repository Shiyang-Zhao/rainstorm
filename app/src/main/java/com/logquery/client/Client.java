package com.logquery.client;

import java.util.Scanner;

import org.apache.commons.lang3.time.StopWatch;

public class Client {
    private static final String[] HOSTS = System.getProperty("hosts").split(",");
    private static final int PORT = Integer.parseInt(System.getProperty("mp1_port"));
    private static final int TIMEOUT = Integer.parseInt(System.getProperty("mp1_timeout"));

    private final Connection connection; // Handles client and server connection
    private final Query query; // Manages query operations

    public Client(Connection connection, Query query) {
        this.connection = connection;
        this.query = query;
    }

    /**
     * Starts the client loop that takes user input, sends the query to all servers,
     * and displays the response times. The loop continues until the user enters
     * 'exit'.
     */
    public void start() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println("Enter your query (or 'exit' to quit): ");
                String queryContent = scanner.nextLine();
                // Exit condition
                if (queryContent.equalsIgnoreCase("exit")) {
                    System.out.println("Exiting...");
                    break;
                }

                // Measure latency time
                StopWatch stopWatch = new StopWatch();
                stopWatch.start();

                // Send query to all hosts and gather responses
                query.sendQueryToAll(queryContent);
                query.receiveResponses(queryContent);

                stopWatch.stop();
                System.out.println("Time taken: " + stopWatch.getTime() + "ms");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            connection.exit();
        }
    }

    public static void main(String[] args) {
        // Initialize connection and query objects
        Connection connection = new Connection(HOSTS, PORT, TIMEOUT);
        Query query = new Query(connection);
        // Create and start the client
        Client client = new Client(connection, query);
        boolean success = client.connection.initialize();
        // If at least one connection is successful, start the client loop
        if (success) {
            client.start();
        }
    }
}
