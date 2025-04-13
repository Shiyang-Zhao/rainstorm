package com.membership.node;

import com.membership.member.Member;
import com.membership.util.Command;
import com.membership.util.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.net.*;
import java.util.concurrent.*;
import java.io.IOException;

public class Node extends Member {
    private static final String INTRODUCER_IP = System.getProperty("mp2_introducer_ip");
    private static final int INTRODUCER_PORT = Integer.parseInt(System.getProperty("mp2_introducer_port"));
    private static final int NODE_PORT = Integer.parseInt(System.getProperty("mp2_node_port"));

    private final DatagramSocket socket;
    private final Map<String, Member> membershipList = new ConcurrentSkipListMap<>(
            Comparator.comparingInt(k -> Utils.getVmId(k)));
    private final ExecutorService ackExecutorService = Executors.newCachedThreadPool();

    public Node(int port) throws SocketException, UnknownHostException {
        super(InetAddress.getLocalHost().getHostAddress(), port);
        this.socket = new DatagramSocket(port);
    }

    /**
     * Starts the node in an infinite loop that continuously listens for incoming
     * UDP packets and handles them. The loop continues until the user enters
     * 'exit'.
     * The node also starts a new thread to listen for incoming packets and to
     * receive commands from the user.
     */
    public void start() {
        Utils.initializeLogger();
        Utils.logger.info("Node started on port " + NODE_PORT);
        System.out.println("Node started on port " + NODE_PORT);
        ackExecutorService.submit(() -> {
            byte[] buffer = new byte[65507];
            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    handleRequest(socket, packet);
                } catch (IOException e) {
                    Utils.logger.severe("Error receiving packet: " + e.getMessage());
                }
            }
        });

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println(
                        "Please enter a command (join, leave, list_mem, list_self, enable_sus, disable_sus, list_sus, status_sus, exit):");
                String commandInput = scanner.nextLine();
                Command command = Command.getCommand(commandInput);
                if (command == null) {
                    continue;
                }
                handleCommand(command);
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
        Utils.logger.info("Sent join request to introducer");
    }

    private void handleLeaveCommand() {
        sendSelfInfotoIntroducer(Command.LEAVE, false);
        Utils.logger.info("Sent leave request to introducer");
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
        Utils.logger.info("Suspicious mode: " + (isSuspectedMode() ? "ON" : "OFF"));
        System.out.println("Suspicious mode: " + (isSuspectedMode() ? "ON" : "OFF"));
        Utils.logger.info("Sent update to introducer: Suspicion mode enabled");
    }

    private void handleDisableSusCommand() {
        setSuspectedMode(false);
        sendSelfInfotoIntroducer(Command.DISABLE_SUS, false);
        Utils.logger.info("Suspicious mode: " + (isSuspectedMode() ? "ON" : "OFF"));
        System.out.println("Suspicious mode: " + (isSuspectedMode() ? "ON" : "OFF"));
        Utils.logger.info("Sent update to introducer: Suspicion mode disabled");
    }

    private void handleStatusSusCommand() {
        System.out.println("Status: " + (getStatus() == 1 ? "ALIVE"
                : getStatus() == 0 ? "SUSPECTED" : getStatus() == -1 ? "FAILED" : "UNKNOWN"));
    }

    private void handleExitCommand() {
        setStatus(-1);
        Utils.logger.info("Exiting node, setting status to FAILED");
    }

    private void sendSelfInfotoIntroducer(Command command, boolean isSendFullInfo) {
        JSONObject dataToSend = new JSONObject()
                .put("command", command.toString());

        if (isSendFullInfo) {
            dataToSend.put("node", super.toJSON());
        } else {
            dataToSend.put("address", super.getAddress());
        }
        Utils.sendUdpPacket(socket, INTRODUCER_IP, INTRODUCER_PORT, dataToSend.toString());
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
     * @param socket The socket used to receive the packet.
     * @param packet The packet received from the node.
     */
    private void handleRequest(DatagramSocket socket, DatagramPacket packet) {
        String receivedData = new String(packet.getData(), 0, packet.getLength()).trim();
        JSONObject receivedJson = new JSONObject(receivedData);
        Command command = Command.getCommand(receivedJson.getString("command"));

        if (command == null) {
            return;
        }

        if (command != Command.PING && command != Command.ACK) {
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
                handlePingRequest(packet, membershipList);
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

    private void handleJoinRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        JSONArray receivedJsonArray = receivedJson.getJSONArray("membershipList");
        for (int i = 0; i < receivedJsonArray.length(); i++) {
            JSONObject memberJson = receivedJsonArray.getJSONObject(i);
            String joiningMemberAddress = memberJson.getString("address");
            membershipList.computeIfAbsent(joiningMemberAddress, key -> {
                Member joiningMember = new Member(
                        joiningMemberAddress,
                        memberJson.getInt("port"),
                        memberJson.getLong("timestamp"),
                        memberJson.getInt("status"),
                        memberJson.getBoolean("isSuspectedMode"));
                Utils.logger
                        .info("VM" + joiningMember.getId() + " joined (" + joiningMemberAddress + ":"
                                + joiningMember.getPort() + ")" + " at " + joiningMember.getTimestamp());
                return joiningMember;
            });

        }
        Utils.logger.info("Membership list updated from received data");
    }

    private void handleLeaveRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String leaveMemberAddress = receivedJson.getString("address");
        membershipList.computeIfPresent(leaveMemberAddress, (key, leavingMember) -> {
            Utils.logger.info("VM " + leavingMember.getId() + " leave (" + leavingMember.getAddress() + ":"
                    + leavingMember.getPort() + ")" + " at " + leavingMember.getTimestamp());
            return null;
        });
    }

    private void handlePingRequest(DatagramPacket packet, Map<String, Member> membershipList) {
        JSONObject pingMessage = new JSONObject()
                .put("command", "ACK")
                .put("address", super.getAddress());

        Utils.sendUdpPacket(socket, packet.getAddress().getHostAddress(), packet.getPort(),
                pingMessage.toString());
        Utils.logger.info("ACK sent to: " + packet.getAddress().getHostAddress() + ":" + packet.getPort());
    }

    private void handleAckRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String ackAddress = receivedJson.getString("address");
        membershipList.computeIfPresent(ackAddress, (key, ackedMember) -> {
            if (ackedMember.getStatus() == 0) { // 0 indicates suspected
                ackedMember.setStatus(1);
                Utils.logger.info("Node " + ackedMember.getId() + " (" + ackedMember.getAddress()
                        + ":" + ackedMember.getPort() + ") is now healthy after ACK");
            } else {
                Utils.logger.info(
                        "Received ACK for a alive node: " + ackedMember.getId() + " - no action needed");
            }
            return ackedMember;
        });
    }

    private void handleEnableSusRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String enableSusAddress = receivedJson.getString("address");

        if (membershipList.containsKey(enableSusAddress)) {
            Member member = membershipList.get(enableSusAddress);
            member.setSuspectedMode(true);
            Utils.logger
                    .info("SUSPICIUS mode on " + "VM" + member.getId() + " (" + member.getAddress() + ":"
                            + member.getPort() + ")"
                            + " at " + member.getTimestamp() + " is ENABLED");
        } else {
            Utils.logger.warning("Node with address " + enableSusAddress + " not found in membership list");
        }
    }

    private void handleDisableSusRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String disableSusAddress = receivedJson.getString("address");

        if (membershipList.containsKey(disableSusAddress)) {
            Member member = membershipList.get(disableSusAddress);
            member.setSuspectedMode(false);
            Utils.logger
                    .info("SUSPICIUS mode" + "VM" + member.getId() + " (" + member.getAddress() + ":"
                            + member.getPort() + ")"
                            + " at " + member.getTimestamp() + " is DISABLED");
        } else {
            Utils.logger
                    .warning("Node with address " + disableSusAddress + " not found in membership list");
        }
    }

    private void handleSuspectedRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String suspectedAddress = receivedJson.getString("address");

        if (membershipList.containsKey(suspectedAddress)) {
            Member member = membershipList.get(suspectedAddress);
            member.setStatus(0);
            Utils.logger.warning(
                    "VM" + member.getId() + " (" + member.getAddress() + ":" + member.getPort() + ")"
                            + " at " + member.getTimestamp() + " is SUSPECTED");
        } else {
            Utils.logger.warning("Node with address " + suspectedAddress + " not found in membership list");
        }
    }

    private void handleFailedRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String failedAddress = receivedJson.getString("address");

        if (membershipList.containsKey(failedAddress)) {
            Member failedMember = membershipList.get(failedAddress);
            failedMember.setStatus(-1);
            membershipList.remove(failedAddress);
            Utils.logger.info("VM" + failedMember.getId() + " (" + failedMember.getAddress() + ":"
                    + failedMember.getPort() + ")" + " at " + failedMember.getTimestamp() + " is FAILED");
        } else {
            Utils.logger.warning("Node with address " + failedAddress + " not found in membership list.");
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
            Node node = new Node(NODE_PORT);
            node.start();
        } catch (Exception e) {
            Utils.logger.severe("Error starting node: " + e.getMessage());
            e.printStackTrace();
        }
    }
}