package com.membership.member;

import org.json.JSONObject;

import com.membership.util.Utils;

public class Member {

    private int id;
    private String address;
    private int port;
    private long timestamp;
    private int status; // 1 = ALIVE, 0 = SUSPECTED, -1 = FAILED
    private boolean isSuspectedMode;

    public Member(String address, int port) {
        this.address = address;
        this.port = port;
        this.timestamp = System.currentTimeMillis();
        this.status = 1;
        this.isSuspectedMode = false;
        this.id = Utils.getVmId(address);
    }

    public Member(String address, int port, long timestamp, int status, boolean isSuspectedMode) {
        this.address = address;
        this.port = port;
        this.timestamp = timestamp;
        this.status = status;
        this.isSuspectedMode = isSuspectedMode;
        this.id = Utils.getVmId(address);
    }

    public int getId() {
        return id;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isSuspectedMode() {
        return isSuspectedMode;
    }

    public void setSuspectedMode(boolean isSuspectedMode) {
        this.isSuspectedMode = isSuspectedMode;
    }

    /**
     * Converts the Member object to a JSON object, where the fields are the
     * same as the fields of the Member object.
     *
     * @return A JSON object representing this Member object.
     */
    public JSONObject toJSON() {
        JSONObject jsonObject = new JSONObject()
                .put("id", id)
                .put("address", address)
                .put("port", port)
                .put("status", status)
                .put("timestamp", timestamp)
                .put("isSuspectedMode", isSuspectedMode);
        return jsonObject;
    }

    /**
     * Converts the Member object to a string, which displays the information
     * about the member in a human-readable format.
     *
     * @param memberIndex The index of the member in the membership list.
     * @return A string displaying the information about the member.
     */
    public String toString(int memberIndex) {
        return "============================================\n" +
                "Member " + memberIndex + " Information:\n" +
                "VM ID: " + id + "\n" +
                "Address: " + address + "\n" +
                "Port: " + port + "\n" +
                "Status: " + (status == 1 ? "Alive" : status == 0 ? "Suspected" : "Failed") + "\n" +
                "Timestamp: " + timestamp + "\n" +
                "Suspicious Mode: " + (isSuspectedMode ? "ON" : "OFF") + "\n" +
                "============================================";
    }

    /**
     * Converts the Member object to a string, which displays the information
     * about the member in a human-readable format.
     *
     * @return A string displaying the information about the member.
     */
    @Override
    public String toString() {
        return "============================================\n" +
                "Member Information:\n" +
                "VM ID: " + id + "\n" +
                "Address: " + address + "\n" +
                "Port: " + port + "\n" +
                "Status: " + (status == 1 ? "Alive" : status == 0 ? "Suspected" : "Failed") + "\n" +
                "Timestamp: " + timestamp + "\n" +
                "Suspicious Mode: " + (isSuspectedMode ? "ON" : "OFF") + "\n" +
                "============================================";
    }
}
