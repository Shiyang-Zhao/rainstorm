package com.streamprocessingsystem.introducer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.streamprocessingsystem.hydfs.HyDFS;
import com.streamprocessingsystem.member.Member;
import com.streamprocessingsystem.rainstorm.RainStorm;
import com.streamprocessingsystem.util.Command;
import com.streamprocessingsystem.util.Utils;

import java.util.*;
import java.net.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.util.stream.Collectors;

public class Introducer extends Member {
    private static final int K = Integer.parseInt(System.getProperty("mp2_k"));
    private static final int INTRODUCER_PORT = Integer.parseInt(System.getProperty("mp2_introducer_port"));
    private static final int PERIOD = Integer.parseInt(System.getProperty("mp2_period"));
    private static final int FAIL_TIMEOUT = Integer.parseInt(System.getProperty("mp2_fail_timeout"));
    private static final int SUSPICION_TIMEOUT = Integer.parseInt(System.getProperty("mp2_suspicion_timeout"));
    private static final int HYDFS_PORT = Integer.parseInt(System.getProperty("mp3_port"));
    private static final int RAINSTORM_PORT = Integer.parseInt(System.getProperty("mp4_port"));

    private final HyDFS hydfs;
    private final RainStorm rainStorm;
    private final DatagramSocket mp2_socket;
    private final DatagramSocket mp3_socket;
    private final DatagramSocket mp4_socket;
    private final Map<Integer, Member> membershipList = new ConcurrentSkipListMap<>();
    private final Map<Integer, ScheduledFuture<?>> failureTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pingExecutorService = Executors.newScheduledThreadPool(K);
    private final ScheduledExecutorService ackExecutorService = Executors.newScheduledThreadPool(K);
    private final ExecutorService requestExecutorService = Executors.newCachedThreadPool();
    private final ExecutorService multicastExecutorService = Executors.newCachedThreadPool();

    public Introducer(int introducerPort, int mp3Port, int mp4Port) throws SocketException, UnknownHostException {
        super(InetAddress.getLocalHost().getHostAddress(), introducerPort);
        this.mp2_socket = new DatagramSocket(introducerPort);
        this.mp3_socket = new DatagramSocket(mp3Port);
        this.mp4_socket = new DatagramSocket(mp4Port);
        membershipList.computeIfAbsent(super.getId(),
                id -> new Member(this.getId(), this.getAddress(), this.getPort(), this.getTimestamp(), this.getStatus(),
                        this.isSuspectedMode()));
        this.hydfs = new HyDFS(mp3_socket, getId(), (ConcurrentSkipListMap<Integer, Member>) membershipList);
        this.rainStorm = new RainStorm(mp4_socket, getId(), getId(), hydfs,
                (ConcurrentSkipListMap<Integer, Member>) membershipList);
    }

    public void start() {
        Utils.logger.info("Introducer started on port " + INTRODUCER_PORT);
        System.out.println("Introducer started on port " + INTRODUCER_PORT);

        pingExecutorService.scheduleAtFixedRate(() -> sendPingToRandomKMembers(membershipList), 0, PERIOD,
                TimeUnit.SECONDS);

        new Thread(() -> {
            while (true) {
                try {
                    byte[] buffer = new byte[65507];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    mp2_socket.receive(packet);
                    requestExecutorService.submit(() -> handleRequest(packet));
                } catch (IOException e) {
                    Utils.logger.severe("Error receiving packet: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();

        new Thread(() -> {
            while (true) {
                try {
                    byte[] buffer = new byte[65507];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    mp3_socket.receive(packet);
                    requestExecutorService.submit(() -> hydfs.handleRequest(packet));
                } catch (IOException e) {
                    Utils.logger.severe("Error receiving packet: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();

        new Thread(() -> {
            while (true) {
                try {
                    byte[] buffer = new byte[65507];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    mp4_socket.receive(packet);
                    requestExecutorService.submit(() -> rainStorm.handleRequest(packet));
                } catch (IOException e) {
                    Utils.logger.severe("Error receiving packet: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println("Please enter a command:");
                String commandInput = scanner.nextLine();
                String[] args = commandInput.trim().split("\\s+", 2);
                Command command = Command.getCommand(args[0]);
                if (command == null) {
                    continue;
                }
                String parameters = args.length > 1 ? args[1] : "";
                if (Command.isMP3Command(command)) {
                    hydfs.handleCommand(command, parameters);
                } else {
                    rainStorm.handleCommand(command, parameters);
                }
            }
        }
    }

    private void handleRequest(DatagramPacket packet) {
        String receivedData = new String(packet.getData(), 0, packet.getLength()).trim();
        JSONObject receivedJson = new JSONObject(receivedData);
        Command command = Command.getCommand(receivedJson.getString("command"));

        if (command == null) {
            return;
        }

        if (command != Command.PING && command != Command.ACK) {
            System.out.println("Received command: " + command);
            Utils.logger.info("Received command: " + command);
        }

        switch (command) {
            case JOIN:
                handleJoinRequest(receivedJson, membershipList);
                break;

            case LEAVE:
                handleLeaveRequest(receivedJson, membershipList);
                break;

            case ACK:
                handleAckRequest(receivedJson, failureTimers, membershipList);
                break;

            case ENABLE_SUS:
                handleEnableSusRequest(receivedJson, membershipList);
                break;

            case DISABLE_SUS:
                handleDisableSusRequest(receivedJson, membershipList);
                break;

            default:
                break;
        }
    }

    private void handleJoinRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        JSONObject jsonMember = receivedJson.getJSONObject("node");
        int id = jsonMember.getInt("id");
        String address = jsonMember.getString("address");
        int port = jsonMember.getInt("port");
        long timestamp = jsonMember.getLong("timestamp");
        int status = jsonMember.getInt("status");
        boolean isSuspectedMode = jsonMember.getBoolean("isSuspectedMode");

        membershipList.computeIfAbsent(id, i -> {
            Member member = new Member(id, address, port, timestamp, status, isSuspectedMode);
            hydfs.handleReplicateCommand(id, true);
            rainStorm.initilizeTupleTracker(id);
            String message = "Node Joined:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + address + ":" + port + "\n" +
                    "    Join Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.info(message);
            return member;
        });
        Utils.listMembership(membershipList);
        multicastMembershipListToAllMembers(Command.JOIN, membershipList);
    }

    private void handleLeaveRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");
        membershipList.computeIfPresent(id, (i, member) -> {
            String message = "Node Leave:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                    "    Leave Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.info(message);
            return null;
        });
        Utils.listMembership(membershipList);
        multicastMemberChangetoAllMembers(Command.LEAVE, id);
    }

    private void handleAckRequest(JSONObject receivedJson, Map<Integer, ScheduledFuture<?>> failureTimers,
            Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");
        failureTimers.compute(id, (i, failureTask) -> {
            if (failureTask != null) {
                boolean isCancelled = failureTask.cancel(false);
                if (isCancelled) {
                    Member member = membershipList.get(id);
                    if (member != null) {
                        member.setStatus(1);
                        multicastMemberChangetoAllMembers(Command.ACK, id);
                    }
                    return null;
                }
            }
            return failureTask;
        });
    }

    private void handleEnableSusRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");

        if (membershipList.containsKey(id)) {
            Member member = membershipList.get(id);
            member.setSuspectedMode(true);
            String message = "Enable Suspicious Mode:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                    "    Enabled Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.info(message);
            multicastMemberChangetoAllMembers(Command.ENABLE_SUS, id);
        } else {
            System.out.println("Node " + id + " not found in membership list");
        }
    }

    private void handleDisableSusRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");

        if (membershipList.containsKey(id)) {
            Member member = membershipList.get(id);
            member.setSuspectedMode(false);
            String message = "Disable Suspicious Mode:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                    "    Disabled Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.info(message);
            multicastMemberChangetoAllMembers(Command.DISABLE_SUS, id);
        } else {
            System.out.println("Node " + id + " not found in membership list");
        }
    }

    private void sendPing(Member member) {
        try {
            JSONObject dataToSend = new JSONObject()
                    .put("command", Command.PING.toString());

            Utils.sendUdpPacket(mp2_socket, member.getAddress(), member.getPort(), dataToSend.toString());

            // Schedule a task to check if the member is still alive
            failureTimers.computeIfAbsent(member.getId(), id -> {
                // Schedule a task to check if the member is still alive after FAIL_TIMEOUT
                return ackExecutorService.schedule(() -> {
                    if (member.isSuspectedMode()) {
                        member.setStatus(0);
                        String suspectedMessage = "Node Suspected:\n" +
                                "    Node ID: " + member.getId() + "\n" +
                                "    Node Name: " + member.getName() + "\n" +
                                "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                                "    Suspected Time: " + member.getTimestamp() + "\n" +
                                "    Status: Waiting for suspicion timeout";
                        System.out.println(suspectedMessage);
                        Utils.logger.warning(suspectedMessage);

                        multicastMemberChangetoAllMembers(Command.SUSPECTED, id);
                        // Schedule another task to mark the node as FAILED after SUSPICION_TIMEOUT
                        ackExecutorService.schedule(() -> {
                            if (member.isSuspectedMode()) {
                                hydfs.handleReplicateCommand(id, false);
                                rainStorm.handleFailedCommand(id);
                                membershipList.remove(id);
                                multicastMemberChangetoAllMembers(Command.FAILED, id);

                                String failedMessage = "Node Failure Detected:\n" +
                                        "    Node ID: " + member.getId() + "\n" +
                                        "    Node Name: " + member.getName() + "\n" +
                                        "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                                        "    Failure Time: " + member.getTimestamp();
                                System.out.println(failedMessage);
                                Utils.logger.warning(failedMessage);
                            }
                        }, SUSPICION_TIMEOUT, TimeUnit.MILLISECONDS);
                    } else {
                        hydfs.handleReplicateCommand(id, false);
                        rainStorm.handleFailedCommand(id);
                        membershipList.remove(id);
                        multicastMemberChangetoAllMembers(Command.FAILED, id);

                        String failedMessage = "Node Failure Detected:\n" +
                                "    Node ID: " + member.getId() + "\n" +
                                "    Node Name: " + member.getName() + "\n" +
                                "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                                "    Failure Time: " + member.getTimestamp();
                        System.out.println(failedMessage);
                        Utils.logger.warning(failedMessage);
                    }
                    failureTimers.remove(id);
                }, FAIL_TIMEOUT, TimeUnit.MILLISECONDS);
            });
        } catch (Exception e) {
            Utils.logger.severe("Error sending ping to " + member.getAddress() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends ping requests to random K nodes in the membership list. If the
     * membership list has fewer than K nodes, it sends pings to all nodes in the
     * list.
     * 
     * @param membershipList The membership list of nodes to ping.
     */
    private void sendPingToRandomKMembers(Map<Integer, Member> membershipList) {
        if (membershipList.isEmpty()) {
            System.out.println("Membership list is empty");
            return;
        }

        List<Member> members = membershipList.values().stream()
                .filter(member -> member.getId() != super.getId())
                .collect(Collectors.toList());

        int nodesToPing = Math.min(K, members.size());
        Collections.shuffle(members);
        for (int i = 0; i < nodesToPing; i++) {
            sendPing(members.get(i));
        }
    }

    /**
     * Multicasts the full membership list to all nodes in the membership list.
     * The JSON object sent to each node will contain the command, the list of
     * members (each represented as a JSON object), and the timestamp of the
     * sender.
     * 
     * @param command        The command to be used in the JSON object.
     * @param membershipList The membership list of nodes to multicast to.
     */
    private void multicastMembershipListToAllMembers(Command command, Map<Integer, Member> membershipList) {
        JSONObject dataToSend = new JSONObject();
        dataToSend.put("command", command.toString());

        JSONArray membersArray = new JSONArray();
        membershipList.forEach((id, member) -> {
            membersArray.put(member.toJSON());
        });
        dataToSend.put("membershipList", membersArray);

        membershipList.forEach((i, member) -> {
            if (i == super.getId()) {
                return;
            }
            multicastExecutorService.submit(() -> {
                try {
                    Utils.sendUdpPacket(mp2_socket, member.getAddress(), member.getPort(), dataToSend.toString());
                    String message = "Multicast Full Membership List:\n" +
                            "    Command: " + command + "\n" +
                            "    Node ID: " + member.getId() + "\n" +
                            "    Node Name: " + member.getName() + "\n" +
                            "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                            "    Timestamp: " + member.getTimestamp();
                    Utils.logger.info(message);
                } catch (Exception e) {
                    Utils.logger.severe("Error multicasting full membership list to Node" + member.getId()
                            + " (" + member.getAddress() + ":" + member.getPort() + "): " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    /**
     * Multicasts a membership change event to all members in the membership list.
     * The JSON object sent to each node will contain the command and the address of
     * the node that was added or removed.
     * 
     * @param command The command to be used in the JSON object, which can be either
     *                "JOIN" or "LEAVE".
     * @param address The IP address of the node that was added or removed.
     */
    private void multicastMemberChangetoAllMembers(Command command, int id) {
        JSONObject dataToSend = new JSONObject()
                .put("command", command.toString())
                .put("id", id);

        membershipList.forEach((i, member) -> {
            if (i == super.getId()) {
                return;
            }
            multicastExecutorService.submit(() -> {
                try {
                    Utils.sendUdpPacket(mp2_socket, member.getAddress(), member.getPort(), dataToSend.toString());
                    if (command != Command.PING && command != Command.ACK) {
                        String message = "Multicast Command:\n" +
                                "    Command: " + command + "\n" +
                                "    Node ID: " + member.getId() + "\n" +
                                "    Node Name: " + member.getName() + "\n" +
                                "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                                "    Timestamp: " + member.getTimestamp();
                        Utils.logger.info(message);
                    }
                } catch (Exception e) {
                    Utils.logger.severe("Error multicasting " + command + " for Node " + member.getAddress() + ": "
                            + e.getMessage() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    /**
     * Starts the introducer in an infinite loop that continuously sends ping
     * requests to random K nodes in the membership list and handles incoming
     * requests from nodes.
     * 
     * @param args The command line arguments, which are not used by this method.
     */
    public static void main(String[] args) {
        try {
            Utils.initializeLogger(InetAddress.getLocalHost().getHostAddress(), HYDFS_PORT);
            Introducer introducer = new Introducer(INTRODUCER_PORT, HYDFS_PORT, RAINSTORM_PORT);
            introducer.start();
        } catch (Exception e) {
            Utils.logger.severe("Error starting introducer: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
