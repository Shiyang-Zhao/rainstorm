package com.membership.util;

import com.membership.member.Member;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Utils {
    private static final String[] HOSTS = System.getProperty("hosts").split(",");
    private static final Path LOG_DIRECTORY = Paths.get(System.getProperty("log_directory"));

    public static Logger logger;

    /**
     * Initializes the logger for the current VM. The logger is configured to
     * write to a file named "vmX.log" in the "src/main/java/com/logquery/server"
     * directory, where X is the zero-based index of the current VM's IP address
     * in the HOSTS array. The logger is also configured to print a message
     * indicating which VM it is logging for.
     * 
     * @throws IOException If an I/O error occurs while initializing the logger.
     */
    public static void initializeLogger() {
        String address = null;
        try {
            address = InetAddress.getLocalHost().getHostAddress();
            int vmId = getVmId(address);
            if (vmId == -1) {
                System.err.println("Invalid IP address: " + address);
                return;
            }
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

    /**
     * Determines the VM ID based on the specified IP address. The VM ID is
     * the one-based index of the IP address in the HOSTS array. If the IP
     * address is not found in the HOSTS array, -1 is returned.
     * 
     * @param address The IP address to determine the VM ID of.
     * @return The VM ID of the IP address, or -1 if not found.
     */
    public static int getVmId(String address) {
        for (int i = 0; i < HOSTS.length; i++) {
            if (HOSTS[i].equals(address)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Prints the membership list to the console. The membership list is expected to
     * be a map of IP addresses to Member objects. If the membership list is empty, a
     * message is printed indicating that there are no active nodes in the membership
     * list. Otherwise, each member in the membership list is printed to the console
     * with its ID, IP address, port, timestamp, status, and whether or not it is in
     * suspected mode. The member ID is the 1-based index of the member in the
     * membership list.
     * 
     * @param membershipList The membership list to print to the console.
     */
    public static void listMembership(Map<String, Member> membershipList) {
        if (membershipList.isEmpty()) {
            System.out.println("No active nodes found in the membership list");
        } else {
            final int[] memberCount = { 1 };
            membershipList.forEach((key, member) -> {
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
     * @param socket The socket to use to send the packet.
     * @param address The hostname or IP address of the host to send the packet to.
     * @param port The port number to send the packet to.
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
