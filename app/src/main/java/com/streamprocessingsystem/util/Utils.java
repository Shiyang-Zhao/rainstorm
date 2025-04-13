package com.streamprocessingsystem.util;

import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import com.streamprocessingsystem.member.Member;

public class Utils {
    private static final String[] HOSTS = System.getProperty("hosts").split(",");
    private static final int M = Integer.parseInt(System.getProperty("mp3_m"));
    private static final Path LOG_DIRECTORY = Paths.get(System.getProperty("log_directory"));

    public static Logger logger;

    public static void initializeLogger(String address, int port) {
        try {
            int vmId = getSha1Id(address + ":" + port);
            logger = Logger.getLogger(Utils.class.getName());
            Handler[] handlers = logger.getHandlers();
            for (Handler handler : handlers) {
                logger.removeHandler(handler);
            }
            Path logFilePath = LOG_DIRECTORY.resolve("vm" + vmId + ".log");
            FileHandler fileHandler = new FileHandler(logFilePath.toString(), true);
            fileHandler.setEncoding(StandardCharsets.UTF_8.name());
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);

            logger.setUseParentHandlers(false);

            logger.info("Logger initialized for VM ID: " + vmId + " (IP: " + address + ")");
        } catch (IOException e) {
            System.err.println("Error initializing logger for IP " + address);
            e.printStackTrace();
        }
    }

    public static String getVmNameByAddress(String address) {
        for (int i = 0; i < HOSTS.length; i++) {
            if (HOSTS[i].trim().equals(address)) {
                return "VM" + (i + 1);
            }
        }
        return "VM not found";
    }

    public static String getAddressByNameIdentifier(int identifier) {
        return HOSTS[identifier - 1];
    }

    private static final ThreadLocal<MessageDigest> sha1Digest = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not found", e);
        }
    });

    public static int getSha1Id(String identifier) {
        MessageDigest sha1 = sha1Digest.get();
        sha1.reset();
        byte[] hashBytes = sha1.digest(identifier.getBytes(StandardCharsets.UTF_8));

        int result = 0;
        int fullBytes = M / 8;
        int remainingBits = M % 8;

        for (int i = 0; i < fullBytes; i++) {
            result = (result << 8) | (hashBytes[i] & 0xff);
        }

        if (remainingBits > 0) {
            int lastByte = hashBytes[fullBytes] & 0xff;
            lastByte = lastByte >> (8 - remainingBits);
            result = (result << remainingBits) | lastByte;
        }

        return Math.abs(result);
    }

    /**
     * Prints the membership list to the console. The membership list is expected to
     * be a map of IP addresses to Member objects. If the membership list is empty,
     * a
     * message is printed indicating that there are no active nodes in the
     * membership
     * list. Otherwise, each member in the membership list is printed to the console
     * with its ID, IP address, port, timestamp, status, and whether or not it is in
     * suspected mode. The member ID is the 1-based index of the member in the
     * membership list.
     * 
     * @param membershipList The membership list to print to the console.
     */
    public static void listMembership(Map<Integer, Member> membershipList) {
        if (membershipList.isEmpty()) {
            System.out.println("No active nodes found in the membership list");
        } else {
            final int[] memberCount = { 1 };
            membershipList.forEach((id, member) -> {
                System.out.println(member.toString(memberCount[0]));
                memberCount[0]++;
            });
        }
    }

    /**
     * Sends a UDP packet to the specified host and port with the given content.
     * If an IOException occurs while sending the packet, an error message is
     * printed to the console, including the error message.
     * 
     * @param socket  The socket to use to send the packet.
     * @param address The hostname or IP address of the host to send the packet to.
     * @param port    The port number to send the packet to.
     * @param content The content of the UDP packet to send.
     */
    public static void sendUdpPacket(DatagramSocket socket, String address, int port, String content) {
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            byte[] messageBytes = content.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(messageBytes, messageBytes.length, inetAddress, port);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
