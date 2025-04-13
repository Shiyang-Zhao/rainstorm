package com.logquery.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Collections;

public class Connection {
    private final String[] hosts;
    private final int port;
    private final int timeout;

    // Data structures to manage socket connections and streams
    private final ConcurrentHashMap<String, Socket> sockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DataInputStream> inputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DataOutputStream> outputs = new ConcurrentHashMap<>();

    // Lists to keep track of successful and failed connections
    private final List<String> successfulConnections = Collections.synchronizedList(new ArrayList<>());
    private final List<String> failedConnections = Collections.synchronizedList(new ArrayList<>());

    // Thread pool to manage concurrent connections
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public Connection(String[] hosts, int port, int timeout) {
        this.hosts = hosts;
        this.port = port;
        this.timeout = timeout;
    }

    public String[] getHosts() {
        return hosts;
    }

    public ConcurrentHashMap<String, Socket> getSockets() {
        return sockets;
    }

    public ConcurrentHashMap<String, DataInputStream> getInputs() {
        return inputs;
    }

    public ConcurrentHashMap<String, DataOutputStream> getOutputs() {
        return outputs;
    }

    public List<String> getSuccessfulConnections() {
        return successfulConnections;
    }

    public List<String> getFailedConnections() {
        return failedConnections;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Initializes connections to all hosts concurrently using an ExecutorService.
     * It connects to each host and logs successful or failed attempts.
     *
     * @return boolean indicating whether there are any successful connections.
     */
    public boolean initialize() {
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < hosts.length; i++) {
            int hostIndex = i;
            Future<Boolean> future = executorService.submit(() -> connect(hostIndex + 1, hosts[hostIndex], port));
            futures.add(future);
        }

        for (Future<Boolean> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        System.out.println("Successful connections: " + successfulConnections.size());
        System.out.println("Failed connections: " + failedConnections.size());

        if (successfulConnections.isEmpty()) {
            System.out.println("No successful connections. Exiting...");
            exit();
            return false;
        }
        return true;
    }

    /**
     * Attempts to connect to a single host. On success, stores the socket and
     * streams.
     * On failure, logs the failed connection.
     *
     * @param id   Unique identifier for the host.
     * @param host IP address of the host.
     * @param port Port number to connect to.
     * @return boolean indicating success or failure of the connection.
     */
    public boolean connect(int id, String host, int port) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeout);

            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            sockets.put(host, socket);
            inputs.put(host, in);
            outputs.put(host, out);

            successfulConnections.add(host);
            System.out.println("Connected to VM " + id + ":" + host + ":" + port);
            return true;
        } catch (Exception e) {
            // On failure, log the error and add to the failed connections list
            failedConnections.add(host);
            System.out.println("Failed to connect to VM " + id + ": " + host + ":" + port);
            return false;
        }
    }

    /**
     * Shuts down the ExecutorService gracefully, waiting for active tasks to
     * complete.
     * If termination is not achieved within the timeout, it forces shutdown.
     *
     * @param executorService The ExecutorService to shut down.
     */
    public void shutdownExecutorService(ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cleans up resources, including closing sockets, input/output streams,
     * and shutting down the ExecutorService. Clears all internal data structures.
     */
    public void exit() {
        shutdownExecutorService(executorService);
        for (String host : sockets.keySet()) {
            try {
                if (inputs.get(host) != null) {
                    inputs.get(host).close();
                }
                if (outputs.get(host) != null) {
                    outputs.get(host).close();
                }
                if (sockets.get(host) != null && !sockets.get(host).isClosed()) {
                    sockets.get(host).close();
                }
            } catch (IOException e) {
                System.err.println("Error when exiting");
                e.printStackTrace();
            }
        }
        // Clear all data structures after closing resources
        sockets.clear();
        inputs.clear();
        outputs.clear();
        successfulConnections.clear();
        failedConnections.clear();
    }
}
