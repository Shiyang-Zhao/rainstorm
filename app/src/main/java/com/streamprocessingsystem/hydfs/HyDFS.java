package com.streamprocessingsystem.hydfs;

import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.streamprocessingsystem.member.Member;
import com.streamprocessingsystem.util.Command;
import com.streamprocessingsystem.util.Utils;

import org.json.JSONObject;

public class HyDFS {
    private static final int N = Integer.parseInt(System.getProperty("mp3_n"));
    private static final int PORT = Integer.parseInt(System.getProperty("mp3_port"));
    private static final String LOCAL_BASE_PATH = System.getProperty("mp3_local_base_path");
    private static final String REMOTE_BASE_PATH = System.getProperty("mp3_remote_base_path");
    private static final int LRUCACHE_SIZE = Integer.parseInt(System.getProperty("mp3_lrucache_size")) * 1024 * 1024;

    private final int id;
    private volatile int lamportTimestamp;
    private final DatagramSocket socket;
    private final ConcurrentSkipListMap<Integer, Member> membershipList;
    private final Cache<String, String> LRUCache;
    private final ConcurrentHashMap<Integer, HyFile> files = new ConcurrentHashMap<>();
    private final ExecutorService requestExecutorService = Executors.newCachedThreadPool();

    public HyDFS(DatagramSocket socket, int id, ConcurrentSkipListMap<Integer, Member> membershipList)
            throws SocketException, UnknownHostException {
        this.id = id;
        this.lamportTimestamp = 0;
        this.socket = socket;
        this.membershipList = membershipList;
        this.LRUCache = Caffeine.newBuilder()
                .maximumWeight(LRUCACHE_SIZE)
                .weigher((String key, String value) -> value.getBytes().length)
                .build();
    }

    public void handleCommand(Command command, String parameters) {
        String[] args = parameters.trim().split("\\s+");
        switch (command) {
            case CREATE:
                if (args.length == 2) {
                    handleCreateCommand(args[0], args[1], true);
                } else {
                    System.out.println("Usage: create <localFileName> <remoteFileName>");
                }
                break;

            case GET:
                if (args.length == 2) {
                    handleGetCommand(args[1], args[0]);
                } else {
                    System.out.println("Usage: get <remoteFileName> <localFileName>");
                }
                break;

            case APPEND:
                if (args.length == 2) {
                    handleAppendCommand(args[0], args[1]);
                } else {
                    System.out.println("Usage: append <localFileName> <remoteFileName>");
                }
                break;

            case MERGE:
                if (args.length == 1) {
                    handleMergeCommand(args[0]);
                } else {
                    System.out.println("Usage: merge <remoteFileName>");
                }
                break;

            case LS:
                if (args.length == 1) {
                    handleLsCommand(args[0]);
                } else {
                    System.out.println("Usage: ls <remoteFileName>");
                }
                break;

            case STORE:
                handleStoreCommand();
                break;

            case GETFROMREPLICA:
                if (args.length == 3) {
                    handleGetFromReplicaCommand(Integer.parseInt(args[0]), args[2], args[1]);
                } else {
                    System.out.println("Usage: getFromReplica <address> <remoteFileName> <localFileName>");
                }
                break;

            case MULTIAPPEND:
                handleMultiAppendCommand(args);
                break;

            default:
                break;
        }
    }

    public void handleCreateCommand(String localFileName, String remoteFileName, boolean isLocal) {
        incrementLamportTimestamp();

        int remoteFileId = Utils.getSha1Id(remoteFileName);
        String content = readFileContent(localFileName, isLocal);
        JSONObject dataToSend = new JSONObject()
                .put("command", Command.CREATE.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("remoteFileId", remoteFileId)
                .put("remoteFileName", remoteFileName)
                .put("content", content);

        List<Member> successors = getNSuccessorsByFileId(remoteFileId, N);
        for (Member successor : successors) {
            requestExecutorService.submit(() -> {
                Utils.sendUdpPacket(socket, successor.getAddress(), PORT, dataToSend.toString());

                // String message = "Sending CREATE command:\n" +
                //         "    " + (isLocal ? "Local File: '" : "Remote File: '") + localFileName + "'\n" +
                //         "    Remote File: '" + remoteFileName + "'\n" +
                //         "    Successor Details:\n" +
                //         "        VM ID: " + successor.getId() + "\n" +
                //         "        VM Name: " + successor.getName() + "\n" +
                //         "        Address: " + successor.getAddress();
                // System.out.println(message);
                // Utils.logger.info(message);
            });
        }
    }

    public void handleGetCommand(String localFileName, String remoteFileName) {
        incrementLamportTimestamp();

        int remoteFileId = Utils.getSha1Id(remoteFileName);
        List<Member> successors = getNSuccessorsByFileId(remoteFileId, N);
        JSONObject dataToSend = new JSONObject()
                .put("command", Command.GET.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("localFileName", localFileName)
                .put("remoteFileName", remoteFileName);

        for (Member successor : successors) {
            requestExecutorService.submit(() -> {
                Utils.sendUdpPacket(socket, successor.getAddress(), PORT, dataToSend.toString());
                // String message = "Sending GET command:\n" +
                //         "    Local File: '" + localFileName + "'\n" +
                //         "    Remote File: '" + remoteFileName + "'\n" +
                //         "    Successor Details:\n" +
                //         "        VM ID: " + successor.getId() + "\n" +
                //         "        VM Name: " + successor.getName() + "\n" +
                //         "        Address: " + successor.getAddress();
                // System.out.println(message);
                // Utils.logger.info(message);
            });
        }
    }

    public void handleAppendCommand(String localFileName, String remoteFileName) {
        incrementLamportTimestamp();

        int remoteFileId = Utils.getSha1Id(remoteFileName);
        List<Member> successors = getNSuccessorsByFileId(remoteFileId, N);
        String content = readFileContent(localFileName, true);

        JSONObject dataToSend = new JSONObject()
                .put("command", Command.APPEND.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("remoteFileId", remoteFileId)
                .put("remoteFileName", remoteFileName)
                .put("content", content);

        for (Member successor : successors) {
            requestExecutorService.submit(() -> {
                Utils.sendUdpPacket(socket, successor.getAddress(), PORT, dataToSend.toString());
                // String message = "Sending APPEND command:\n" +
                //         "    Local File: '" + localFileName + "'\n" +
                //         "    Remote File: '" + remoteFileName + "'\n" +
                //         "    Successor Details:\n" +
                //         "        VM ID: " + successor.getId() + "\n" +
                //         "        VM Name: " + successor.getName() + "\n" +
                //         "        Address: " + successor.getAddress();
                // System.out.println(message);
                // Utils.logger.info(message);
            });
        }
    }

    public void handleMergeCommand(String remoteFileName) {
        incrementLamportTimestamp();

        int remoteFileId = Utils.getSha1Id(remoteFileName);
        List<Member> successors = getNSuccessorsByFileId(remoteFileId, N);

        JSONObject dataToSend = new JSONObject()
                .put("command", Command.MERGE.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("remoteFileId", remoteFileId)
                .put("remoteFileName", remoteFileName);

        for (Member successor : successors) {
            requestExecutorService.submit(() -> {
                Utils.sendUdpPacket(socket, successor.getAddress(), PORT, dataToSend.toString());
                // String message = "Sending MERGE command:\n" +
                //         "    Remote File: '" + remoteFileName + "'\n" +
                //         "    Successor Details:\n" +
                //         "        VM ID: " + successor.getId() + "\n" +
                //         "        VM Name: " + successor.getName() + "\n" +
                //         "        Address: " + successor.getAddress() + ":" + PORT + "\n";
                // System.out.println(message);
                // Utils.logger.info(message);
            });
        }
    }

    public void handleLsCommand(String remoteFileName) {
        int remoteFileId = Utils.getSha1Id(remoteFileName);
        List<Member> successors = getNSuccessorsByFileId(remoteFileId, N);
        System.out.println("File ID: " + remoteFileId);
        for (Member successor : successors) {
            System.out.println(successor.toString());
        }
    }

    public void handleStoreCommand() {
        files.forEach((id, hyFile) -> {
            System.out.println(hyFile.toString());
        });
    }

    public void handleGetFromReplicaCommand(int identifier, String localFileName, String remoteFileName) {
        incrementLamportTimestamp();

        String address = Utils.getAddressByNameIdentifier(identifier);
        JSONObject dataToSend = new JSONObject()
                .put("command", Command.GET.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("localFileName", localFileName)
                .put("remoteFileName", remoteFileName);

        requestExecutorService.submit(() -> {
            Utils.sendUdpPacket(socket, address, PORT, dataToSend.toString());
            // String message = "Sending GETFROMREPLICA command:\n" +
            //         "    Local File: '" + localFileName + "'\n" +
            //         "    Remote File: '" + remoteFileName + "'\n" +
            //         "    Successor Details:\n" +
            //         "        VM ID: " + Utils.getSha1Id(address + ":" + PORT) + "\n" +
            //         "        VM Name: " + Utils.getVmNameByAddress(address) + "\n" +
            //         "        Address: " + address;
            // System.out.println(message);
            // Utils.logger.info(message);
        });
    }

    public void handleMultiAppendCommand(String[] args) {
        incrementLamportTimestamp();

        String remoteFileName = args[0];
        int num = (args.length - 1) / 2;
        for (int i = 1; i <= num; i++) {
            String address = Utils.getAddressByNameIdentifier(Integer.parseInt(args[i]));
            String localFileName = args[i + num];
            JSONObject dataToSend = new JSONObject()
                    .put("command", Command.MULTIAPPEND.toString())
                    .put("lamportTimestamp", lamportTimestamp)
                    .put("localFileName", localFileName)
                    .put("remoteFileName", remoteFileName);

            requestExecutorService.submit(() -> {
                Utils.sendUdpPacket(socket, address, PORT, dataToSend.toString());
                // String message = "Sending MULTIAPPEND command:\n" +
                //         "    Local File: '" + localFileName + "'\n" +
                //         "    Remote File: '" + remoteFileName + "'\n" +
                //         "    Successor Details:\n" +
                //         "        VM ID: " + Utils.getSha1Id(address + ":" + PORT) + "\n" +
                //         "        VM Name: " + Utils.getVmNameByAddress(address) + "\n" +
                //         "        Address: " + address;
                // System.out.println(message);
                // Utils.logger.info(message);
            });
        }
    }

    // Join id or Fail id
    public void handleReplicateCommand(int id, boolean join) {
        incrementLamportTimestamp();

        Member successor = getSuccessor(id);
        JSONObject dataToSend = new JSONObject()
                .put("command", Command.REPLICATE.toString())
                .put("lamportTimestamp", lamportTimestamp);

        if (!join) {
            Member predecessor = getPredecessor(id);
            requestExecutorService.submit(() -> {
                Utils.sendUdpPacket(socket, predecessor.getAddress(), PORT, dataToSend.toString());
                Utils.sendUdpPacket(socket, successor.getAddress(), PORT, dataToSend.toString());
                // String message = "Sending FAIL_REPLICATE command:\n" +
                //         "    Successor Details:\n" +
                //         "        VM ID: " + successor.getId() + "\n" +
                //         "        VM Name: " + successor.getName() + "\n" +
                //         "        Address: " + successor.getAddress() + ":" + PORT + "\n" +
                //         "    Predecessor Details:\n" +
                //         "        VM ID: " + predecessor.getId() + "\n" +
                //         "        VM Name: " + predecessor.getName() + "\n" +
                //         "        Address: " + predecessor.getAddress() + ":" + PORT;
                // System.out.println(message);
                // Utils.logger.info(message);
            });
        } else {
            requestExecutorService.submit(() -> {
                Utils.sendUdpPacket(socket, successor.getAddress(), PORT, dataToSend.toString());
                // String message = "Sending FAIL_REPLICATE command to Successor only:\n" +
                //         "    Successor Details:\n" +
                //         "        VM ID: " + successor.getId() + "\n" +
                //         "        VM Name: " + successor.getName() + "\n" +
                //         "        Address: " + successor.getAddress() + ":" + PORT;
                // System.out.println(message);
                // Utils.logger.info(message);
            });
        }
    }

    public void handleRequest(DatagramPacket packet) {
        String receivedData = new String(packet.getData(), 0, packet.getLength()).trim();
        JSONObject receivedJson = new JSONObject(receivedData);
        Command command = Command.getCommand(receivedJson.getString("command"));
        String address = packet.getAddress().getHostAddress();
        int port = packet.getPort();
        // System.out.println("Received " + command.toString() + " command from " + address + ":" + port);

        switch (command) {
            case CREATE:
                handleCreateRequest(address, port, receivedJson);
                break;

            case GET:
                handleGetRequest(address, port, receivedJson);
                break;

            case GET_ACK:
                handleGetAckRequest(receivedJson);
                break;

            case APPEND:
                handleAppendRequest(receivedJson);
                break;

            case MERGE:
                handleMergeRequest(receivedJson);
                break;

            case MERGE_ACK:
                handleMergeAckRequest(receivedJson);
                break;

            case GETFROMREPLICA:
                handleGetFromReplicaRequest(address, port, receivedJson);
                break;

            case MULTIAPPEND:
                handleMultiAppendRequest(receivedJson);
                break;

            case REPLICATE:
                handleReplicateRequest(receivedJson);
                break;

            default:
                break;
        }
    }

    public void handleCreateRequest(String address, int port, JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        int remoteFileId = receivedJson.getInt("remoteFileId");
        String remoteFileName = receivedJson.getString("remoteFileName");
        String content = receivedJson.getString("content");

        if (files.containsKey(remoteFileId)) {
            // String message = "File already exists at remote path: " + REMOTE_BASE_PATH + "/" + remoteFileName;
            // System.out.println(message);
            // Utils.logger.info(message);
            return;
        }

        boolean success = writeFileContent(remoteFileName, content, false, false);
        if (!success) {
            return;
        }
        updateLamportTimestamp(receivedLamportTimestamp);
        files.computeIfAbsent(remoteFileId, key -> new HyFile(remoteFileName));
        // files.get(remoteFileId).addLog(id, Command.CREATE, lamportTimestamp,
        //         remoteFileName, content);
        // String message = success
        //         ? "File created successfully at remote path: " + REMOTE_BASE_PATH + "/" + remoteFileName
        //         : "Failed to create file at remote path: " + REMOTE_BASE_PATH + "/" + remoteFileName;
        // System.out.println(message);
        // Utils.logger.info(message);
    }

    public void handleGetRequest(String address, int port, JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        String localFileName = receivedJson.getString("localFileName");
        String remoteFileName = receivedJson.getString("remoteFileName");
        String content = readFileContent(remoteFileName, false);
        updateLamportTimestamp(receivedLamportTimestamp);

        JSONObject dataToSend = new JSONObject()
                .put("command", Command.GET_ACK.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("localFileName", localFileName)
                .put("content", content);

        Utils.sendUdpPacket(socket, address, port, dataToSend.toString());
        // String message = "Sending GET_ACK for local file '" + localFileName + "' with content to address: "
        //         + address + ", port: " + port + ".";
        // System.out.println(message);
        // Utils.logger.info(message);
    }

    public void handleGetAckRequest(JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        String localFileName = receivedJson.getString("localFileName");
        String content = receivedJson.getString("content");

        if (receivedLamportTimestamp > lamportTimestamp) {
            boolean success = writeFileContent(localFileName, content, true, false);
            if (!success) {
                return;
            }
            updateLamportTimestamp(receivedLamportTimestamp);
            // String message = "File content is saved in file '" + localFileName + "'.";
            // System.out.println(message);
            // Utils.logger.info(message);
        }
    }

    public void handleAppendRequest(JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        int remoteFileId = receivedJson.getInt("remoteFileId");
        String remoteFileName = receivedJson.getString("remoteFileName");
        String content = receivedJson.getString("content");

        boolean success = writeFileContent(remoteFileName, content, false, true);
        if (!success) {
            return;
        }
        updateLamportTimestamp(receivedLamportTimestamp);
        // files.get(remoteFileId).addLog(id, Command.APPEND, lamportTimestamp, remoteFileName, content);
        // String message = success
        //         ? "Content appended successfully to remote file: " + REMOTE_BASE_PATH + "/" + remoteFileName
        //         : "Failed to append content to remote file: " + REMOTE_BASE_PATH + "/" + remoteFileName;
        // System.out.println(message);
        // Utils.logger.info(message);
    }

    public void handleMergeRequest(JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        int remoteFileId = receivedJson.getInt("remoteFileId");
        String remoteFileName = receivedJson.getString("remoteFileName");
        updateLamportTimestamp(receivedLamportTimestamp);

        List<Member> successors = getNSuccessorsByFileId(remoteFileId, N);
        // if (successors.isEmpty() || id != successors.get(0).getId()) {
        // return;
        // }

        String content = readFileContent(remoteFileName, false);
        JSONObject dataToSend = new JSONObject()
                .put("command", Command.MERGE_ACK.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("remoteFileId", remoteFileId)
                .put("remoteFileName", remoteFileName)
                .put("content", content);

        for (Member successor : successors) {
            if (successor.getId() != id) {
                requestExecutorService.submit(() -> {
                    Utils.sendUdpPacket(socket, successor.getAddress(), PORT, dataToSend.toString());
                });
            }
        }
    }

    public void handleMergeAckRequest(JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        int remoteFileId = receivedJson.getInt("remoteFileId");
        String remoteFileName = receivedJson.getString("remoteFileName");
        String content = receivedJson.getString("content");

        if (receivedLamportTimestamp > lamportTimestamp) {
            boolean success = writeFileContent(remoteFileName, content, false, false);
            if (!success) {
                return;
            }
            updateLamportTimestamp(receivedLamportTimestamp);
            // files.get(remoteFileId).getLogs().clear();
            // String message = "Merge complete: File content is saved in file '" +
            //         remoteFileName + "'.";
            // System.out.println(message);
            // Utils.logger.info(message);
        }
    }

    public void handleGetFromReplicaRequest(String address, int port, JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        String localFileName = receivedJson.getString("localFileName");
        String remoteFileName = receivedJson.getString("remoteFileName");
        updateLamportTimestamp(receivedLamportTimestamp);

        String content = readFileContent(remoteFileName, false);
        JSONObject dataToSend = new JSONObject()
                .put("command", Command.GETFROMREPLICA_ACK.toString())
                .put("lamportTimestamp", lamportTimestamp)
                .put("localFileName", localFileName)
                .put("content", content);

        requestExecutorService.submit(() -> {
            Utils.sendUdpPacket(socket, address, port, dataToSend.toString());
        });

        // String message = "Sending GETFROMREPLICA_ACK for local file '" + localFileName + "' with content to address: "
        //         + address + ", port: " + port + ".";
        // System.out.println(message);
        // Utils.logger.info(message);
    }

    public void handleGetFromReplicaAckRequest(JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        String localFileName = receivedJson.getString("localFileName");
        String content = receivedJson.getString("content");
        boolean success = writeFileContent(localFileName, content, true, false);
        if (!success) {
            return;
        }
        updateLamportTimestamp(receivedLamportTimestamp);
    }

    public void handleMultiAppendRequest(JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        String localFileName = receivedJson.getString("localFileName");
        String remoteFileName = receivedJson.getString("remoteFileName");
        updateLamportTimestamp(receivedLamportTimestamp);
        handleAppendCommand(localFileName, remoteFileName);
    }

    public void handleReplicateRequest(JSONObject receivedJson) {
        int receivedLamportTimestamp = receivedJson.getInt("lamportTimestamp");
        updateLamportTimestamp(receivedLamportTimestamp);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(REMOTE_BASE_PATH))) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    String remoteFileName = file.getFileName().toString();
                    handleCreateCommand(remoteFileName, remoteFileName, false);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading files in directory: " + e.getMessage());
        }
    }

    public Member getPredecessor(int id) {
        Map.Entry<Integer, Member> previousEntry = membershipList.lowerEntry(id);
        return previousEntry != null ? previousEntry.getValue() : membershipList.lastEntry().getValue();
    }

    public Member getSuccessor(int id) {
        Map.Entry<Integer, Member> nextEntry = membershipList.higherEntry(id);
        return nextEntry != null ? nextEntry.getValue() : membershipList.firstEntry().getValue();
    }

    public Member getNodeByRemoteFileId(int remoteFileId) {
        Map.Entry<Integer, Member> entry = membershipList.ceilingEntry(remoteFileId);
        return entry != null ? entry.getValue() : membershipList.firstEntry().getValue();
    }

    public List<Member> getNSuccessorsByFileId(int fileId, int n) {
        List<Member> successors = new ArrayList<>();
        Member member = getNodeByRemoteFileId(fileId);

        for (int i = 0; i < n; i++) {
            successors.add(member);
            member = getSuccessor(member.getId());
        }

        return successors;
    }

    public String readFileContent(String fileName, boolean isLocal) {
        if (isLocal) {
            String content = LRUCache.getIfPresent(fileName);
            if (content != null) {
                // System.out.println("Cache hit: Retrieved content for '" + fileName + "' from cache.");
                return content;
            }
        }
        String basePath = isLocal ? LOCAL_BASE_PATH : REMOTE_BASE_PATH;
        String path = Paths.get(basePath, fileName).toString();
        // String message = "Reading content from " + (isLocal ? "local" : "remote") + " file: '" + path + "'.\n";
        // System.out.println(message);
        // Utils.logger.info(message);

        try {
            String content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            if (isLocal) {
                LRUCache.put(fileName, content);
            }
            return content;
        } catch (IOException e) {
            System.err.println("Error reading file content: " + e.getMessage());
            return null;
        }
    }

    public Stream<String> readFileContentAsStream(String fileName, boolean isLocal) {
        String basePath = isLocal ? LOCAL_BASE_PATH : REMOTE_BASE_PATH;
        String path = Paths.get(basePath, fileName).toString();
        // String message = "Reading content as stream from " + (isLocal ? "local" : "remote") + " file: '" + path
        //         + "'.\n";
        // System.out.println(message);
        // Utils.logger.info(message);

        try {
            return Files.lines(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Error reading file content: " + e.getMessage());
            return Stream.empty();
        }
    }

    public synchronized boolean writeFileContent(String fileName, String content, boolean isLocal, boolean append) {
        String basePath = isLocal ? LOCAL_BASE_PATH : REMOTE_BASE_PATH;
        File file = new File(basePath, fileName);
        // String message = "Writing content to " + (isLocal ? "local" : "remote") + " file: '" + file.getPath()
        //         + "', append mode: " + append + ".\n";
        // System.out.println(message);
        // Utils.logger.info(message);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, append))) {
            writer.write(content);
            // String successMessage = "Write successful for file: '" + file.getPath() + "'.";
            // System.out.println(successMessage);
            // Utils.logger.info(successMessage);
            return true;
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean writeFileContentAsStream(String fileName, Stream<String> content, boolean isLocal,
            boolean append) {
        String basePath = isLocal ? LOCAL_BASE_PATH : REMOTE_BASE_PATH;
        File file = new File(basePath, fileName);

        // String message = "Writing content to " + (isLocal ? "local" : "remote") + " file: '" + file.getPath()
        //         + "', append mode: " + append + ".\n";
        // System.out.println(message);
        // Utils.logger.info(message);

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            content.forEach(line -> {
                try {
                    writer.write(line);
                    writer.newLine();
                } catch (IOException e) {
                    throw new RuntimeException("Error writing to file: " + e.getMessage(), e);
                }
            });
            // String successMessage = "Write successful for file: '" + file.getPath() + "'.";
            // System.out.println(successMessage);
            // Utils.logger.info(successMessage);
            return true;
        } catch (Exception e) {
            System.err.println("Error writing to file: " + e.getMessage());
            return false;
        }
    }

    public synchronized void incrementLamportTimestamp() {
        lamportTimestamp += 1;
    }

    public synchronized void updateLamportTimestamp(int receivedLamportTimestamp) {
        lamportTimestamp = Math.max(lamportTimestamp, receivedLamportTimestamp) + 1;
    }

    public void clear() {
        files.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(REMOTE_BASE_PATH))) {
            for (Path file : stream) {
                Files.delete(file);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
