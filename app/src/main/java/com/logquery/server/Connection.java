package com.logquery.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;

public class Connection {
    private final int port;
    private final Path logDirectory;
    private ServerSocket serverSocket;
    private boolean running;

    public Connection(int port, Path logDirectory) {
        this.port = port;
        this.logDirectory = logDirectory;
        this.running = false;
    }

    /**
     * Starts the server and listens for incoming client connections. For each
     * client, it processes
     * commands, executes them via the `GrepAPI`, and sends back the results. The
     * server continues
     * to listen and process requests until the "exit" command is received or the
     * server is stopped.
     */
    public void listen() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            System.out.println("Server is listening on port: " + port);

            while (running) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected");
                try (
                        DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                        DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {
                    // Loop to handle client commands until the connection is closed
                    while (running && !clientSocket.isClosed()) {
                        String command = in.readUTF();
                        System.out.println("Received command: " + command);

                        // Check for exit command to stop the server
                        if (command.equalsIgnoreCase("exit")) {
                            stop();
                            break;
                        }
                        // Execute the grep command and retrieve the result
                        GrepAPI grepCommand = new GrepAPI(command, logDirectory);
                        String result = grepCommand.executeCommand();

                        // Send the result back to the client
                        byte[] resultBytes = result.getBytes("UTF-8");
                        out.write((resultBytes.length >> 24) & 0xFF);
                        out.write((resultBytes.length >> 16) & 0xFF);
                        out.write((resultBytes.length >> 8) & 0xFF);
                        out.write((resultBytes.length) & 0xFF);
                        out.write(resultBytes);
                        out.flush();
                    }
                } catch (Exception e) {
                    System.out.println("Client disconnected");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Stops the server by closing the server socket and setting the running flag to
     * false.
     * This method gracefully shuts down the server when called.
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                System.out.println("Server stopped.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
