package com.streamprocessingsystem.rainstorm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

import com.streamprocessingsystem.hydfs.HyDFS;
import com.streamprocessingsystem.member.Member;
import com.streamprocessingsystem.util.Command;
import com.streamprocessingsystem.util.Utils;

public class RainStorm {
    private static final int PORT = Integer.parseInt(System.getProperty("mp4_port"));
    private static final int LOG_TIMEOUT = Integer.parseInt(System.getProperty("mp4_log_timeout"));
    private static final int NUM_CHUNKS = Integer.parseInt(System.getProperty("mp4_num_chunks"));
    private static final String CLASS_PATH = System.getProperty("mp4_class_path");
    private static final String BASE_PATH = System.getProperty("mp4_base_path");

    private final int id;
    private final int introducerId;
    private final DatagramSocket socket;
    private final HyDFS hydfs;
    private final ConcurrentSkipListMap<Integer, Member> membershipList;
    private final ProcessBuilder processBuilder;
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, Tuple>> tupleTracker = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> counter = new ConcurrentHashMap<>();
    private final AtomicBoolean isExecutorStarted = new AtomicBoolean(false);
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService logWriterExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService requestExecutorService = Executors.newCachedThreadPool();

    public RainStorm(DatagramSocket socket, int id, int introducerId, HyDFS hydfs,
            ConcurrentSkipListMap<Integer, Member> membershipList) throws SocketException, UnknownHostException {
        this.id = id;
        this.introducerId = introducerId;
        this.socket = socket;
        this.hydfs = hydfs;
        this.membershipList = membershipList;
        this.processBuilder = new ProcessBuilder();
    }

    public void handleCommand(Command command, String parameters) {
        Pattern pattern = Pattern.compile("\\[(.*?)\\]|\\S+");
        List<String> args = parseCommandParameters(parameters, pattern);

        if (args.size() < 7) {
            System.err.println(
                    "Invalid command. Expected format: <op1_exe> <op2_exe> <hydfs_src_file> <hydfs_dest_filename> [<pattern1>] [<pattern2>] <num_tasks>");
            return;
        }

        String op1 = args.get(0);
        String op2 = args.get(1);
        String src = args.get(2);
        String dest = args.get(3);
        String pattern1 = args.get(4);
        String pattern2 = args.get(5);
        int numTasks = Integer.parseInt(args.get(6));

        switch (command) {
            case RAINSTORM:
                startLogWriterExecutor(dest, true);
                handleRainStormCommand(parameters, op1, op2, src, dest, pattern1, pattern2, numTasks, true);
                break;

            default:
                break;
        }
    }

    public void handleRainStormCommand(String parameters, String op1, String op2, String src, String dest,
            String pattern1,
            String pattern2, int numTasks, boolean isLocal) {
        List<Integer> leastNLoadedWorkers = getNLeastLoadedWorkers(numTasks);
        Stream<String> lineStream = hydfs.readFileContentAsStream(src, isLocal);
        List<List<String>> partitions = getPartitionsFromStream(lineStream, numTasks);

        IntStream.range(0, numTasks).forEach(i -> {
            requestExecutorService.submit(() -> {
                int nodeId = leastNLoadedWorkers.get(i);
                try {
                    List<String> tuples = IntStream.range(0, partitions.get(i).size())
                            .mapToObj(index -> {
                                String key = src + ":" + (index + 1);
                                String value = partitions.get(i).get(index);
                                Tuple tuple = new Tuple(nodeId, key, value, parameters, Command.OP1);
                                addTuple(nodeId, tuple);
                                return tuple.getTupleId() + ":" + value;
                            })
                            .toList();

                    List<List<String>> chunks = getChunksFromStringList(tuples, NUM_CHUNKS);
                    for (List<String> chunk : chunks) {
                        JSONObject dataToSend = new JSONObject()
                                .put("command", Command.OP1.toString())
                                .put("nodeId", nodeId)
                                .put("op1", op1)
                                .put("op2", op2)
                                .put("src", src)
                                .put("dest", dest)
                                .put("pattern1", pattern1)
                                .put("pattern2", pattern2)
                                .put("numTasks", numTasks)
                                .put("tuples", chunk);

                        Member worker = membershipList.get(nodeId);
                        Utils.sendUdpPacket(socket, worker.getAddress(), PORT, dataToSend.toString());
                        System.out
                                .println("Sent OP1 command to worker " + nodeId + " with " + chunk.size() + " tuples.");
                    }
                } catch (Exception e) {
                    System.err.println("Error sending OP1 command to worker " + nodeId + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    public void handleFailedCommand(int failedId) {
        ConcurrentHashMap<String, Tuple> failedTuples = tupleTracker.remove(failedId);
        if (failedTuples == null || failedTuples.isEmpty()) {
            System.out.println("No tuples found for failed worker " + failedId);
            return;
        }
        Pattern pattern = Pattern.compile("\\[(.*?)\\]|\\S+");
        int workerId = getLeastLoadedWorker();
        Member worker = membershipList.get(workerId);

        String parameters = failedTuples.values().iterator().next().getParameters();
        List<String> args = parseCommandParameters(parameters, pattern);
        String op1 = args.get(0);
        String op2 = args.get(1);
        String src = args.get(2);
        String dest = args.get(3);
        String pattern1 = args.get(4);
        String pattern2 = args.get(5);
        int numTasks = Integer.parseInt(args.get(6));

        List<String> op1Input = failedTuples.values().stream()
                .filter(tuple -> tuple.getStage() == Command.OP1)
                .map(Tuple::getValue)
                .collect(Collectors.toList());

        List<String> op2Input = failedTuples.values().stream()
                .filter(tuple -> tuple.getStage() == Command.OP2)
                .map(Tuple::getValue)
                .collect(Collectors.toList());

        List<List<String>> op1Chunks = getChunksFromStringList(op1Input, NUM_CHUNKS);
        List<List<String>> op2Chunks = getChunksFromStringList(op2Input, NUM_CHUNKS);

        if (!op1Input.isEmpty()) {
            for (List<String> chunk : op1Chunks) {
                requestExecutorService.submit(() -> {
                    JSONObject dataToSend = new JSONObject()
                            .put("command", Command.OP1.toString())
                            .put("nodeId", workerId)
                            .put("op1", op1)
                            .put("op2", op2)
                            .put("src", src)
                            .put("dest", dest)
                            .put("pattern1", pattern1)
                            .put("pattern2", pattern2)
                            .put("numTasks", numTasks)
                            .put("tuples", chunk);

                    Utils.sendUdpPacket(socket, worker.getAddress(), PORT, dataToSend.toString());
                    String message = "State recovery: " + chunk.size() + " tuples sent to worker " + workerId;
                    System.out.println(message);
                    logs.add(message);
                });
            }
        }

        if (!op2Input.isEmpty()) {
            for (List<String> chunk : op2Chunks) {
                requestExecutorService.submit(() -> {
                    JSONObject dataToSend = new JSONObject()
                            .put("command", Command.OP2.toString())
                            .put("nodeId", workerId)
                            .put("op1", op1)
                            .put("op2", op2)
                            .put("src", src)
                            .put("dest", dest)
                            .put("pattern1", pattern1)
                            .put("pattern2", pattern2)
                            .put("numTasks", numTasks)
                            .put("tuples", chunk);

                    Utils.sendUdpPacket(socket, worker.getAddress(), PORT, dataToSend.toString());
                    String message = "State recovery: " + op2Input.size() + " tuples sent to worker " + workerId;
                    System.out.println(message);
                    logs.add(message);
                });
            }
        }

        tupleTracker.compute(workerId, (id, existingMap) -> {
            if (existingMap == null)
                existingMap = new ConcurrentHashMap<>();
            failedTuples.forEach(existingMap::put);
            return existingMap;
        });
    }

    private void startLogWriterExecutor(String dest, boolean isLocal) {
        if (isExecutorStarted.compareAndSet(false, true) && id == introducerId) {
            logWriterExecutor.scheduleAtFixedRate(() -> {
                try {
                    synchronized (logs) {
                        if (!logs.isEmpty()) {
                            String logContent = String.join("\n", logs);
                            hydfs.writeFileContent(dest, logContent, isLocal, true);
                            logs.clear();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error writing logs: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 0, LOG_TIMEOUT, TimeUnit.MILLISECONDS);
            System.out.println("Log writer executor started");
        }
    }

    public void handleRequest(DatagramPacket packet) {
        String receivedData = new String(packet.getData(), 0, packet.getLength()).trim();
        JSONObject receivedJson = new JSONObject(receivedData);
        Command command = Command.getCommand(receivedJson.getString("command"));

        switch (command) {
            case OP1:
                handleOp1Request(receivedJson);
                break;

            case OP1_ACK:
                handleOp1AckRequest(receivedJson);
                break;

            case OP2:
                handleOp2Request(receivedJson);
                break;

            case OP2_ACK:
                handleOp2AckRequest(receivedJson);
                break;

            default:
                break;
        }
    }

    public void handleOp1Request(JSONObject receivedJson) {
        int nodeId = receivedJson.getInt("nodeId");
        String op1 = receivedJson.getString("op1");
        String op2 = receivedJson.getString("op2");
        String src = receivedJson.getString("src");
        String dest = receivedJson.getString("dest");
        String pattern1 = receivedJson.getString("pattern1");
        String pattern2 = receivedJson.getString("pattern2");
        int numTasks = receivedJson.getInt("numTasks");

        Stream<String> tupleStream = receivedJson.getJSONArray("tuples").toList().stream()
                .map(Object::toString)
                .filter(tuple -> {
                    String[] parts = tuple.split(":", 2);
                    if (parts.length < 2) {
                        return false;
                    }
                    String tupleId = parts[0];
                    Map<String, Tuple> map = tupleTracker.get(id);
                    if (map == null) {
                        return true;
                    }
                    return !map.containsKey(tupleId);
                });

        List<List<String>> partitions = getPartitionsFromStream(tupleStream, numTasks);

        if (partitions.stream().allMatch(List::isEmpty)) {
            System.out.println("All tuples have been processed. Discarding...");
            return;
        }

        IntStream.range(0, numTasks).forEach(i -> {
            requestExecutorService.submit(() -> {
                String input = String.join("\n", partitions.get(i));
                List<String> results = new ArrayList<>();

                try (Stream<String> resultStream = runJavaExecutable(op1, pattern1, input)) {
                    resultStream.forEach(line -> {
                        if (line == null || line.trim().isEmpty()) {
                            return;
                        }
                        String[] parts = line.split(";", 4);
                        String tupleId = parts[0].trim();
                        String objectId = parts[1].trim();
                        String signType = parts[2].trim();
                        addTuple(nodeId, new Tuple(tupleId, nodeId, objectId, signType, Command.OP1_ACK));
                        synchronized (results) {
                            results.add(line);
                        }
                    });

                    List<List<String>> chunks = getChunksFromStringList(results, NUM_CHUNKS);
                    for (List<String> chunk : chunks) {
                        JSONObject dataToSend = new JSONObject()
                                .put("command", Command.OP1_ACK.toString())
                                .put("nodeId", nodeId)
                                .put("op1", op1)
                                .put("op2", op2)
                                .put("src", src)
                                .put("dest", dest)
                                .put("pattern1", pattern1)
                                .put("pattern2", pattern2)
                                .put("numTasks", numTasks)
                                .put("results", chunk);

                        Member leader = membershipList.get(introducerId);
                        Utils.sendUdpPacket(socket, leader.getAddress(), PORT, dataToSend.toString());
                        System.out.println("Sent OP1_ACK chunk to leader with nodeId: " + nodeId);
                    }
                } catch (Exception e) {
                    System.err.println("Error processing OP1 for partition: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    public void handleOp1AckRequest(JSONObject receivedJson) {
        int nodeId = receivedJson.getInt("nodeId");
        String op1 = receivedJson.getString("op1");
        String op2 = receivedJson.getString("op2");
        String src = receivedJson.getString("src");
        String dest = receivedJson.getString("dest");
        String pattern1 = receivedJson.getString("pattern1");
        String pattern2 = receivedJson.getString("pattern2");
        int numTasks = receivedJson.getInt("numTasks");
        String parameters = buildCommandParameters(op1, op2, src, dest, pattern1, pattern2, numTasks);

        List<Integer> leastNLoadedWorkers = getNLeastLoadedWorkers(numTasks);
        JSONArray results = receivedJson.getJSONArray("results");

        Stream<String> resultStream = IntStream.range(0, results.length())
                .mapToObj(i -> {
                    try {
                        String line = results.getString(i);
                        // System.out.println(line);
                        if (line == null || line.trim().isEmpty()) {
                            return null;
                        }
                        String[] parts = line.split(";", 4);
                        String tupleId = parts[0].trim();
                        String objectId = parts[1].trim();
                        String signType = parts[2].trim();
                        String content = parts[3].trim();
                        removeTuple(nodeId, tupleId);

                        String message = objectId + ";" + signType;
                        System.out.println(message);
                        logs.add(message);

                        int workerId = leastNLoadedWorkers.get(i % numTasks);
                        Tuple tuple = new Tuple(workerId, tupleId, content, parameters, Command.OP2);
                        addTuple(workerId, tuple);
                        return tuple.getTupleId() + ":" + content;
                    } catch (Exception e) {
                        System.err.println("Error processing line at index " + i + ": " + e.getMessage());
                        e.printStackTrace();
                        return null;
                    }
                })
                .filter(line -> line != null);

        List<List<String>> partitions = getPartitionsFromStream(resultStream, numTasks);
        IntStream.range(0, numTasks).forEach(i -> {
            requestExecutorService.submit(() -> {
                try {
                    int workerId = leastNLoadedWorkers.get(i);
                    List<String> partition = partitions.get(i);

                    List<List<String>> chunks = getChunksFromStringList(partition, NUM_CHUNKS);

                    for (List<String> chunk : chunks) {
                        JSONObject dataToSend = new JSONObject()
                                .put("command", Command.OP2.toString())
                                .put("nodeId", workerId)
                                .put("op1", op1)
                                .put("op2", op2)
                                .put("src", src)
                                .put("dest", dest)
                                .put("pattern1", pattern1)
                                .put("pattern2", pattern2)
                                .put("numTasks", numTasks)
                                .put("tuples", chunk);

                        Member worker = membershipList.get(workerId);
                        Utils.sendUdpPacket(socket, worker.getAddress(), PORT, dataToSend.toString());
                        System.out.println("Sent OP2 chunk to worker " + workerId);
                    }
                } catch (Exception e) {
                    System.err.println("Error sending OP2 request: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    public void handleOp2Request(JSONObject receivedJson) {
        int nodeId = receivedJson.getInt("nodeId");
        String op1 = receivedJson.getString("op1");
        String op2 = receivedJson.getString("op2");
        String src = receivedJson.getString("src");
        String dest = receivedJson.getString("dest");
        String pattern1 = receivedJson.getString("pattern1");
        String pattern2 = receivedJson.getString("pattern2");
        int numTasks = receivedJson.getInt("numTasks");

        Stream<String> tupleStream = receivedJson.getJSONArray("tuples").toList().stream()
                .map(Object::toString)
                .filter(tuple -> {
                    String[] parts = tuple.split(":", 2);
                    if (parts.length < 2) {
                        return false;
                    }
                    String tupleId = parts[0];
                    Map<String, Tuple> map = tupleTracker.get(id);
                    if (map == null) {
                        return true;
                    }
                    return !map.containsKey(tupleId);
                });

        List<List<String>> partitions = getPartitionsFromStream(tupleStream, numTasks);

        if (partitions.stream().allMatch(List::isEmpty)) {
            System.out.println("All tuples have been processed. Discarding...");
            return;
        }

        IntStream.range(0, numTasks).forEach(i -> {
            requestExecutorService.submit(() -> {
                String input = String.join("\n", partitions.get(i));
                List<String> results = new ArrayList<>();

                try (Stream<String> resultStream = runJavaExecutable(op2, pattern2, input)) {
                    resultStream.forEach(line -> {
                        if (line == null || line.trim().isEmpty()) {
                            return;
                        }
                        String[] parts = line.split(";", 2);
                        String tupleId = parts[0].trim();
                        String category = parts[1].trim();
                        addTuple(nodeId, new Tuple(tupleId, nodeId, tupleId, category, Command.OP2_ACK));
                        synchronized (results) {
                            results.add(line);
                        }
                    });

                    List<List<String>> chunks = getChunksFromStringList(results, NUM_CHUNKS);
                    for (List<String> chunk : chunks) {
                        JSONObject dataToSend = new JSONObject()
                                .put("command", Command.OP2_ACK.toString())
                                .put("nodeId", nodeId)
                                .put("op1", op1)
                                .put("op2", op2)
                                .put("src", src)
                                .put("dest", dest)
                                .put("pattern1", pattern1)
                                .put("pattern2", pattern2)
                                .put("numTasks", numTasks)
                                .put("results", chunk);

                        Member leader = membershipList.get(introducerId);
                        Utils.sendUdpPacket(socket, leader.getAddress(), PORT, dataToSend.toString());
                        System.out.println("Sent OP2_ACK chunk to leader with nodeId: " + nodeId);
                    }
                } catch (Exception e) {
                    System.err.println("Error processing OP2 for partition " + i + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    public void handleOp2AckRequest(JSONObject receivedJson) {
        int nodeId = receivedJson.getInt("nodeId");
        JSONArray results = receivedJson.getJSONArray("results");

        IntStream.range(0, results.length()).forEach(i -> {
            try {
                String line = results.getString(i);
                // System.out.println(line);
                if (line == null || line.trim().isEmpty()) {
                    return;
                }
                String[] parts = line.split(";", 2);
                String tupleId = parts[0].trim();
                String category = parts[1].trim();
                removeTuple(nodeId, tupleId);
                counter.merge(category, 1, Integer::sum);

                String message = "Category: " + category + "\nCount: " + counter.get(category)
                        + "\nTotal Categories: " + counter.size();
                System.out.println(message);
                logs.add(message);
            } catch (Exception e) {
                System.err.println("Error processing result at index " + i + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public Stream<String> runJavaExecutable(String fileName, String pattern, String inputLine) throws Exception {
        String className = fileName.substring(0, fileName.lastIndexOf("."));
        synchronized (processBuilder) {
            processBuilder.command("java", "-XX:-UsePerfData", "-cp", BASE_PATH, CLASS_PATH + "." + className, pattern);
        }
        Process process = processBuilder.start();
        try (var writer = process.getOutputStream()) {
            writer.write(inputLine.getBytes());
            writer.flush();
        }

        BufferedReader outputReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        Future<Stream<String>> outputFuture = requestExecutorService.submit(outputReader::lines);

        requestExecutorService.submit(() -> {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                errorReader.lines().forEach(line -> System.err.println("[Child JVM Warning] " + line));
            } catch (IOException e) {
                System.err.println("Error closing errorReader: " + e.getMessage());
                e.printStackTrace();
            }
        });

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(fileName + " process exited with code " + exitCode);
        }

        return outputFuture.get();
    }

    public List<List<String>> getPartitionsFromStream(Stream<String> stream, int numTasks) {
        List<String> lines = stream
                .filter(line -> line != null && !line.trim().isEmpty())
                .collect(Collectors.toList());

        List<List<String>> partitions = IntStream.range(0, numTasks)
                .mapToObj(i -> new ArrayList<String>())
                .collect(Collectors.toList());

        for (int i = 0; i < lines.size(); i++) {
            partitions.get(i % numTasks).add(lines.get(i));
        }
        return partitions;
    }

    public void initilizeTupleTracker(int workerId) {
        tupleTracker.computeIfAbsent(workerId, k -> new ConcurrentHashMap<>());
    }

    // public void addTuple(int workerId, Tuple tuple) {
    // tupleTracker.computeIfAbsent(workerId, k -> new ConcurrentHashMap<>())
    // .put(tuple.getTupleId(), tuple);
    // }

    public void addTuple(int workerId, Tuple tuple) {
        ConcurrentHashMap<String, Tuple> workerTuples = tupleTracker.computeIfAbsent(workerId,
                k -> new ConcurrentHashMap<>());
        Tuple oldValue = workerTuples.put(tuple.getTupleId(), tuple);
        if (oldValue != null) {
            System.out.println("Tuple with ID " + tuple.getTupleId() + " already existed and was replaced");
        }
    }

    public void removeTuple(int workerId, String tupleId) {
        Optional.ofNullable(tupleTracker.get(workerId)).ifPresent(workerTuples -> workerTuples.remove(tupleId));
    }

    public Integer getLeastLoadedWorker() {
        return tupleTracker.entrySet().stream()
                .min(Comparator
                        .comparingInt(
                                (Map.Entry<Integer, ConcurrentHashMap<String, Tuple>> entry) -> entry.getValue().size())
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public List<Integer> getNLeastLoadedWorkers(int n) {
        return tupleTracker.entrySet().stream()
                .sorted(Comparator
                        .comparingInt(
                                (Map.Entry<Integer, ConcurrentHashMap<String, Tuple>> entry) -> entry.getValue().size())
                        .thenComparingInt(Map.Entry::getKey))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    public static List<List<String>> getChunksFromStringList(List<String> list, int chunkSize) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            chunks.add(list.subList(i, Math.min(list.size(), i + chunkSize)));
        }
        return chunks;
    }

    public List<String> parseCommandParameters(String parameters, Pattern pattern) {
        Matcher matcher = pattern.matcher(parameters);
        List<String> args = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                args.add(matcher.group(1));
            } else {
                args.add(matcher.group());
            }
        }
        return args;
    }

    public String buildCommandParameters(String op1, String op2, String src, String dest, String pattern1,
            String pattern2, int numTasks) {
        return String.format("%s %s %s %s [%s] [%s] %d", op1, op2, src, dest, pattern1, pattern2, numTasks);
    }
}
