package com.membership.introducer;

import com.membership.member.Member;
import com.membership.util.Command;
import com.membership.util.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.net.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.util.stream.Collectors;

public class Introducer extends Member {
    private static final String INTRODUCER_IP = System.getProperty("mp2_introducer_ip");
    private static final int INTRODUCER_PORT = Integer.parseInt(System.getProperty("mp2_introducer_port"));
    private static final int K = Integer.parseInt(System.getProperty("mp2_k"));
    private static final int PERIOD = Integer.parseInt(System.getProperty("mp2_period"));
    private static final int FAIL_TIMEOUT = Integer.parseInt(System.getProperty("mp2_fail_timeout"));
    private static final int SUSPICION_TIMEOUT = Integer.parseInt(System.getProperty("mp2_suspicion_timeout"));

    private DatagramSocket socket;
    private final Map<String, Member> membershipList = new ConcurrentSkipListMap<String, Member>(
            Comparator.comparingInt(k -> Utils.getVmId(k)));
    private final Map<String, ScheduledFuture<?>> failureTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pingExecutorService = Executors.newScheduledThreadPool(K);
    private final ScheduledExecutorService ackExecutorService = Executors.newScheduledThreadPool(K);
    private final ExecutorService requestExecutorService = Executors.newCachedThreadPool();
    private final ExecutorService multicastExecutorService = Executors.newCachedThreadPool();

    public Introducer(int port) throws SocketException, UnknownHostException {
        super(INTRODUCER_IP, INTRODUCER_PORT);
        socket = new DatagramSocket(port);
        membershipList.computeIfAbsent(INTRODUCER_IP, k -> new Member(k, INTRODUCER_PORT));
    }

    public void start() {
        Utils.logger.info("Introducer started on port " + INTRODUCER_PORT);
        System.out.println("Introducer started on port " + INTRODUCER_PORT);
        pingExecutorService.scheduleAtFixedRate(() -> sendPingToRandomKMembers(membershipList), 0, PERIOD,
                TimeUnit.SECONDS);
        while (true) {
            try {
                byte[] buffer = new byte[8192];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                requestExecutorService.submit(() -> handleRequest(socket, packet));
            } catch (IOException e) {
                Utils.logger.severe("Error receiving packet: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Handles incoming requests from nodes. The request is expected to be a JSON
     * object with a "command" field. Depending on the command, the method takes
     * different actions. For example, if the command is "JOIN", the method adds
     * the node to the membership list and multicasts the updated list to all
     * nodes. If the command is "LEAVE", the method removes the node from the
     * membership list and multicasts the updated list to all nodes.
     * 
     * @param socket The socket used to receive the request.
     * @param packet The packet received from the node.
     */
    private synchronized void handleRequest(DatagramSocket socket, DatagramPacket packet) {
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

    private void handleJoinRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        JSONObject memberJson = receivedJson.getJSONObject("node");
        String joiningMemberAddress = memberJson.getString("address");
        membershipList.computeIfAbsent(joiningMemberAddress, key -> {
            Member joiningMember = new Member(
                    joiningMemberAddress,
                    memberJson.getInt("port"));
            Utils.logger
                    .info("VM" + joiningMember.getId() + " joined (" + joiningMemberAddress + ":"
                            + joiningMember.getPort() + ")" + " at " + joiningMember.getTimestamp());
            return joiningMember;
        });
        Utils.listMembership(membershipList);
        multicastMembershipListToAllMembers(Command.JOIN, membershipList);
    }

    private void handleLeaveRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String leaveMemberAddress = receivedJson.getString("address");
        membershipList.computeIfPresent(leaveMemberAddress, (key, leavingMember) -> {
            Utils.logger.info("VM " + leavingMember.getId() + " leave (" + leavingMember.getAddress() + ":"
                    + leavingMember.getPort() + ")" + " at " + leavingMember.getTimestamp());
            return null;
        });
        Utils.listMembership(membershipList);
        multicastMemberChangetoAllMembers(Command.LEAVE, leaveMemberAddress);
    }

    private void handleAckRequest(JSONObject receivedJson, Map<String, ScheduledFuture<?>> failureTimers,
            Map<String, Member> membershipList) {
        String ackAddress = receivedJson.getString("address");
        failureTimers.compute(ackAddress, (key, failureTask) -> {
            if (failureTask != null) {
                boolean wasCancelled = failureTask.cancel(false);
                if (wasCancelled) {
                    Member ackedMember = membershipList.get(ackAddress);
                    if (ackedMember != null) {
                        ackedMember.setStatus(1);
                        multicastMemberChangetoAllMembers(Command.ACK, ackAddress);
                        // Utils.logger.info("Received ACK from VM " + ackedMember.getId() + " ("
                        // + ackedMember.getAddress()
                        // + ":" + ackedMember.getPort() + ") - node is alive");
                    }
                    // Remove task from failureTimers after successful cancellation
                    return null;
                }
            }
            // Keep the task if cancellation wasn't successful
            return failureTask;
        });
    }

    private void handleEnableSusRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String enableSusAddress = receivedJson.getString("address");

        if (membershipList.containsKey(enableSusAddress)) {
            Member member = membershipList.get(enableSusAddress);
            member.setSuspectedMode(true);
            Utils.logger.info("Enable SUSPICIOUS mode for VM" + member.getId() + " (" + member.getAddress()
                    + ":" + member.getPort() + ")" + " at " + member.getTimestamp());
            multicastMemberChangetoAllMembers(Command.ENABLE_SUS, enableSusAddress);
        } else {
            System.out.println("Node with address " + enableSusAddress + " not found in membership list");
        }
    }

    private void handleDisableSusRequest(JSONObject receivedJson, Map<String, Member> membershipList) {
        String disableSusAddress = receivedJson.getString("address");

        if (membershipList.containsKey(disableSusAddress)) {
            Member member = membershipList.get(disableSusAddress);
            member.setSuspectedMode(false);
            Utils.logger.info("Disable SUSPICIOUS mode for VM" + member.getId() + " (" + member.getAddress()
                    + ":" + member.getPort() + ")" + " at " + member.getTimestamp());
            multicastMemberChangetoAllMembers(Command.DISABLE_SUS, disableSusAddress);
        } else {
            Utils.logger
                    .warning("Node with address " + disableSusAddress + " not found in membership list");
        }
    }

    /**
     * Sends a ping to the specified member and schedules a task to check
     * if the member responds within a certain time period. If the member
     * does not respond, it is marked as failed and removed from the
     * membership list.
     * 
     * @param member The member to send the ping to.
     */
    private void sendPing(Member member) {
        try {
            JSONObject pingMessage = new JSONObject()
                    .put("command", Command.PING.toString())
                    .put("address", INTRODUCER_IP);
            String pingContent = pingMessage.toString();
            String address = member.getAddress();

            Utils.sendUdpPacket(socket, address, member.getPort(), pingContent);

            // Schedule a task to check if the member is still alive
            failureTimers.computeIfAbsent(member.getAddress(), key -> {
                // Schedule a task to check if the member is still alive after FAIL_TIMEOUT
                return ackExecutorService.schedule(() -> {
                    if (member.isSuspectedMode()) {
                        Utils.logger
                                .warning("VM" + member.getId() + " (" + member.getAddress() + ":" + member.getPort()
                                        + ")"
                                        + " is SUSPECTED, waiting for suspicion timeout");
                        System.out.println(
                                "VM" + member.getId() + " (" + member.getAddress() + ":" + member.getPort() + ")"
                                        + " is SUSPECTED, waiting for suspicion timeout");

                        // Multicast that the node is suspected
                        multicastMemberChangetoAllMembers(Command.SUSPECTED, address);
                        // Schedule another task to mark the node as FAILED after SUSPICION_TIMEOUT
                        ackExecutorService.schedule(() -> {
                            if (member.isSuspectedMode()) {
                                // Mark the member as failed
                                member.setStatus(-1);
                                membershipList.remove(member.getAddress());
                                multicastMemberChangetoAllMembers(Command.FAILED, address);
                                Utils.logger
                                        .warning("VM" + member.getId() + " (" + member.getAddress() + ":"
                                                + member.getPort()
                                                + ")" + " at " + member.getTimestamp() + " FAILED");
                                System.out.println(
                                        "VM" + member.getId() + " (" + member.getAddress() + ":" + member.getPort()
                                                + ")"
                                                + " at " + member.getTimestamp() + " FAILED");
                            }
                            failureTimers.remove(member.getAddress());
                        }, SUSPICION_TIMEOUT, TimeUnit.MILLISECONDS);
                    } else {
                        // Directly mark the member as failed if not in suspicion mode
                        member.setStatus(-1);
                        membershipList.remove(member.getAddress());
                        multicastMemberChangetoAllMembers(Command.FAILED, address);
                        Utils.logger
                                .warning("VM" + member.getId() + " (" + member.getAddress() + ":" + member.getPort()
                                        + ")"
                                        + " at " + member.getTimestamp() + " failed");
                        System.out.println(
                                "VM" + member.getId() + " (" + member.getAddress() + ":" + member.getPort() + ")"
                                        + " at " + member.getTimestamp() + " failed");
                    }
                    failureTimers.remove(member.getAddress());
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
    private void sendPingToRandomKMembers(Map<String, Member> membershipList) {
        if (membershipList.isEmpty()) {
            // Utils.logger.info("No nodes in membership list");
            return;
        }

        List<Member> members = membershipList.values().stream()
                .filter(member -> !member.getAddress().equals(INTRODUCER_IP))
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
    private void multicastMembershipListToAllMembers(Command command, Map<String, Member> membershipList) {
        JSONObject updateJson = new JSONObject();
        updateJson.put("command", command.toString());

        JSONArray membersArray = new JSONArray();
        membershipList.forEach((key, member) -> {
            membersArray.put(member.toJSON());
        });
        updateJson.put("membershipList", membersArray);
        String updateContent = updateJson.toString();

        membershipList.forEach((key, member) -> {
            if (key.equals(INTRODUCER_IP)) {
                return;
            }
            multicastExecutorService.submit(() -> {
                try {
                    Utils.sendUdpPacket(socket, member.getAddress(), member.getPort(), updateContent);
                    Utils.logger
                            .info("Multicasted full membership list for command: " + command + " for VM"
                                    + member.getId()
                                    + " (" + member.getAddress() + ":" + member.getPort() + ")"
                                    + " at " + member.getTimestamp());
                } catch (Exception e) {
                    Utils.logger.severe("Error multicasting full membership list to VM" + member.getId()
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
    private void multicastMemberChangetoAllMembers(Command command, String address) {
        JSONObject leaveJson = new JSONObject()
                .put("command", command.toString())
                .put("address", address);

        membershipList.forEach((key, mem) -> {
            if (key.equals(INTRODUCER_IP)) {
                return;
            }
            multicastExecutorService.submit(() -> {
                try {
                    Utils.sendUdpPacket(socket, mem.getAddress(), mem.getPort(), leaveJson.toString());
                    if (command != Command.PING && command != Command.ACK) {
                        Utils.logger
                                .info("Multicasted " + command + " to member: " + mem.getAddress() + ":"
                                        + mem.getPort());
                    }
                } catch (Exception e) {
                    Utils.logger.severe("Error multicasting " + command + " to node " + key + ": " + e.getMessage());
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
            Utils.initializeLogger();
            Introducer introducer = new Introducer(INTRODUCER_PORT);
            introducer.start();
        } catch (Exception e) {
            Utils.logger.severe("Error starting introducer: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
