package com.logquery.server;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Server {
    private static final int PORT = 5000;
    private static final Path LOG_DIRECTORY = Paths.get(System.getProperty("log_directory"));
    private static Connection connection;

    public static void main(String[] args) {
        connection = new Connection(PORT, LOG_DIRECTORY);

        // Start a new thread to listen for client connections
        new Thread(() -> {
            connection.listen();
        }).start();

        System.out.println("Server is running on port " + PORT);
    }
}
