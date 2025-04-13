package com.streamprocessingsystem.node;

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

public class Node extends Member {
    private static final String INTRODUCER_IP = System.getProperty("mp2_introducer_ip");
    private static final int INTRODUCER_PORT = Integer.parseInt(System.getProperty("mp2_introducer_port"));
    private static final int NODE_PORT = Integer.parseInt(System.getProperty("mp2_node_port"));
    private static final int HYDFS_PORT = Integer.parseInt(System.getProperty("mp3_port"));
    private static final int RAINSTORM_PORT = Integer.parseInt(System.getProperty("mp4_port"));

    private final HyDFS hydfs;
    private final RainStorm rainStorm;
    private final DatagramSocket mp2_socket;
    private final DatagramSocket mp3_socket;
    private final DatagramSocket mp4_socket;
    private final Map<Integer, Member> membershipList = new ConcurrentSkipListMap<>();
    private final ExecutorService requestExecutorService = Executors.newCachedThreadPool();

    public Node(int nodePort, int mp3Port, int mp4Port) throws SocketException, UnknownHostException {
        super(InetAddress.getLocalHost().getHostAddress(), nodePort);
        this.mp2_socket = new DatagramSocket(nodePort);
        this.mp3_socket = new DatagramSocket(mp3Port);
        this.mp4_socket = new DatagramSocket(mp4Port);
        this.hydfs = new HyDFS(mp3_socket, getId(), (ConcurrentSkipListMap<Integer, Member>) membershipList);
        this.rainStorm = new RainStorm(mp4_socket, getId(), Utils.getSha1Id(INTRODUCER_IP + ":" + INTRODUCER_PORT),
                hydfs, (ConcurrentSkipListMap<Integer, Member>) membershipList);
    }

    /**
     * Starts the node in an infinite loop that continuously listens for incoming
     * UDP packets and handles them. The loop continues until the user enters
     * 'exit'.
     * The node also starts a new thread to listen for incoming packets and to
     * receive commands from the user.
     */
    public void start() {
        Utils.logger.info("Node started on port " + NODE_PORT);
        System.out.println("Node started on port " + NODE_PORT);
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
                    handleCommand(command);
                }
            }
        }
    }

    private void handleCommand(Command command) {
        switch (command) {
            case JOIN:
                handleJoinCommand();
                break;

            case LEAVE:
                handleLeaveCommand();
                break;

            case LIST_MEM:
                handleListMemCommand();
                break;

            case LIST_SELF:
                handleListSelfCommand();
                break;

            case ENABLE_SUS:
                handleEnableSusCommand();
                break;

            case DISABLE_SUS:
                handleDisableSusCommand();
                break;

            case STATUS_SUS:
                handleStatusSusCommand();
                break;

            case EXIT:
                handleExitCommand();
                break;

            default:
                break;
        }
    }

    private void handleJoinCommand() {
        sendSelfInfotoIntroducer(Command.JOIN, true);
        System.out.println("Sent JOIN request to introducer");
        Utils.logger.info("Sent JOIN request to introducer");
        hydfs.clear();
    }

    private void handleLeaveCommand() {
        sendSelfInfotoIntroducer(Command.LEAVE, false);
        System.out.println("Sent LEAVE request to introducer");
        Utils.logger.info("Sent LEAVE request to introducer");
        membershipList.clear();
    }

    private void handleListMemCommand() {
        Utils.listMembership(membershipList);
    }

    private void handleListSelfCommand() {
        System.out.println(super.toString());
    }

    private void handleEnableSusCommand() {
        setSuspectedMode(true);
        sendSelfInfotoIntroducer(Command.ENABLE_SUS, false);
        System.out.println("Suspicious mode: " + (isSuspectedMode() ? "ON" : "OFF"));
        Utils.logger.info("Sent update to introducer: Suspicion mode enabled");
    }

    private void handleDisableSusCommand() {
        setSuspectedMode(false);
        sendSelfInfotoIntroducer(Command.DISABLE_SUS, false);
        System.out.println("Suspicious mode: " + (isSuspectedMode() ? "ON" : "OFF"));
        Utils.logger.info("Sent update to introducer: Suspicion mode disabled");
    }

    private void handleStatusSusCommand() {
        System.out.println("Status: " + (getStatus() == 1 ? "ALIVE"
                : getStatus() == 0 ? "SUSPECTED" : getStatus() == -1 ? "FAILED" : "UNKNOWN"));
    }

    private void handleExitCommand() {
        // setStatus(-1);
        System.out.println("Node exiting...");
        Utils.logger.info("Node exiting...");
        System.exit(0);
    }

    private void sendSelfInfotoIntroducer(Command command, boolean isSendFullInfo) {
        JSONObject dataToSend = new JSONObject()
                .put("command", command.toString());

        if (isSendFullInfo) {
            dataToSend.put("node", super.toJSON());
        } else {
            dataToSend.put("id", super.getId());
        }
        Utils.sendUdpPacket(mp2_socket, INTRODUCER_IP, INTRODUCER_PORT, dataToSend.toString());
    }

    /**
     * Handles incoming UDP packets from other nodes. The packet is expected to be a
     * JSON object with a "command" field. Depending on the command, the method
     * takes
     * different actions. For example, if the command is "JOIN", the method adds the
     * node to the membership list and multicasts the updated list to all nodes. If
     * the command is "LEAVE", the method removes the node from the membership list
     * and
     * multicasts the updated list to all nodes.
     * 
     * @param packet The packet received from the node.
     */
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

            case PING:
                handlePingRequest(packet.getAddress().getHostAddress(), packet.getPort(), membershipList);
                break;

            case ACK:
                handleAckRequest(receivedJson, membershipList);
                break;

            case ENABLE_SUS:
                handleEnableSusRequest(receivedJson, membershipList);
                break;

            case DISABLE_SUS:
                handleDisableSusRequest(receivedJson, membershipList);
                break;

            case SUSPECTED:
                handleSuspectedRequest(receivedJson, membershipList);
                break;

            case FAILED:
                handleFailedRequest(receivedJson, membershipList);
                break;

            default:
                break;
        }
    }

    private void handleJoinRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        JSONArray jsonMembershipList = receivedJson.getJSONArray("membershipList");
        for (int j = 0; j < jsonMembershipList.length(); j++) {
            JSONObject jsonMember = jsonMembershipList.getJSONObject(j);
            int id = jsonMember.getInt("id");
            String address = jsonMember.getString("address");
            int port = jsonMember.getInt("port");
            long timestamp = jsonMember.getLong("timestamp");
            int status = jsonMember.getInt("status");
            boolean isSuspectedMode = jsonMember.getBoolean("isSuspectedMode");

            membershipList.computeIfAbsent(id, i -> {
                Member member = new Member(id, address, port, timestamp, status, isSuspectedMode);
                String message = "Node Join:\n" +
                        "    Node ID: " + member.getId() + "\n" +
                        "    Node Name: " + member.getName() + "\n" +
                        "    Address: " + address + ":" + port + "\n" +
                        "    Join Time: " + member.getTimestamp();
                System.out.println(message);
                Utils.logger.info(message);
                return member;
            });
        }
        Utils.logger.info("Membership list updated from received data");
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
    }

    private void handlePingRequest(String address, int port, Map<Integer, Member> membershipList) {
        JSONObject dataToSend = new JSONObject()
                .put("command", Command.ACK.toString())
                .put("id", super.getId());

        Utils.sendUdpPacket(mp2_socket, address, port, dataToSend.toString());
        // Utils.logger.info("ACK sent to: " + address + ":" + port);
    }

    private void handleAckRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");
        membershipList.computeIfPresent(id, (i, member) -> {
            if (member.getStatus() == 0) { // 0 indicates suspected
                member.setStatus(1);
                String message = "Node Alive:\n" +
                        "    Node ID: " + member.getId() + "\n" +
                        "    Node Name: " + member.getName() + "\n" +
                        "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                        "    Status: ALIVE (Previously SUSPECTED)" + "\n" +
                        "    Alive Time: " + member.getTimestamp();
                System.out.println(message);
                Utils.logger.info(message);
            }
            return member;
        });
    }

    private void handleEnableSusRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");

        if (membershipList.containsKey(id)) {
            Member member = membershipList.get(id);
            member.setSuspectedMode(true);
            String message = "Suspicious Mode Enabled:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                    "    Enabled Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.info(message);
        } else {
            System.out.println("Node " + id + " not found in membership list");
        }
    }

    private void handleDisableSusRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");

        if (membershipList.containsKey(id)) {
            Member member = membershipList.get(id);
            member.setSuspectedMode(false);
            String message = "Suspicious Mode Disabled:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                    "    Disabled Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.info(message);
        } else {
            System.out.println("Node " + id + " not found in membership list");
        }
    }

    private void handleSuspectedRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");

        if (membershipList.containsKey(id)) {
            Member member = membershipList.get(id);
            member.setStatus(0);
            String message = "Node Suspected:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                    "    Suspected Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.warning(message);
        } else {
            System.out.println("Node " + id + " not found in membership list");
        }
    }

    private void handleFailedRequest(JSONObject receivedJson, Map<Integer, Member> membershipList) {
        int id = receivedJson.getInt("id");

        if (membershipList.containsKey(id)) {
            Member member = membershipList.get(id);
            // member.setStatus(-1);
            membershipList.remove(id);
            String message = "Node Failed:\n" +
                    "    Node ID: " + member.getId() + "\n" +
                    "    Node Name: " + member.getName() + "\n" +
                    "    Address: " + member.getAddress() + ":" + member.getPort() + "\n" +
                    "    Failed Time: " + member.getTimestamp();
            System.out.println(message);
            Utils.logger.info(message);
        } else {
            System.out.println("Node " + id + " not found in membership list");
        }
    }

    /**
     * Starts a new node and runs it in an infinite loop that continuously
     * listens for incoming UDP packets and handles them. The loop continues
     * until the user enters 'exit'.
     * 
     * @param args The command line arguments, none of which are used.
     */
    public static void main(String[] args) {
        try {
            Utils.initializeLogger(InetAddress.getLocalHost().getHostAddress(), HYDFS_PORT);
            Node node = new Node(NODE_PORT, HYDFS_PORT, RAINSTORM_PORT);
            node.start();
        } catch (Exception e) {
            Utils.logger.severe("Error starting node: " + e.getMessage());
            e.printStackTrace();
        }
    }
}