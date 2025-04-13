package com.streamprocessingsystem.hydfs;

import com.streamprocessingsystem.util.Utils;

public class HyFile {
    // File id
    private final int id;
    private final String remoteFileName;
    // private final PriorityQueue<Log> logs;

    public HyFile(String remoteFileName) {
        this.id = Utils.getSha1Id(remoteFileName);
        this.remoteFileName = remoteFileName;
        // this.logs = new PriorityQueue<>(
        //         (log1, log2) -> {
        //             int comparison = Integer.compare(log1.getLamportTimestamp(), log2.getLamportTimestamp());
        //             if (comparison != 0) {
        //                 return comparison;
        //             } else {
        //                 return Integer.compare(log1.getId(), log2.getId());
        //             }
        //         });
    }

    public int getId() {
        return id;
    }

    public String getRemoteFileName() {
        return remoteFileName;
    }

    // public PriorityQueue<Log> getLogs() {
    //     return logs;
    // }

    // public synchronized void addLog(int id, Command command, int lamportTimestamp, String remoteFileName,
    //         String content) {
    //     logs.add(new Log(id, command, lamportTimestamp, remoteFileName, content));
    // }

    @Override
    public String toString() {
        return "============================================\n" +
                "File Information:\n" +
                "File ID: " + id + "\n" +
                "Remote File Name: " + remoteFileName + "\n" +
                "============================================\n";
    }

    // public static class Log {
    //     // Node id for consistent order of logs
    //     private final int id;
    //     private final Command command;
    //     private final int lamportTimestamp;
    //     private final String remoteFileName;
    //     private final String content;

    //     public Log(int id, Command command, int lamportTimestamp, String remoteFileName, String content) {
    //         this.id = id;
    //         this.command = command;
    //         this.lamportTimestamp = lamportTimestamp;
    //         this.remoteFileName = remoteFileName;
    //         this.content = content;
    //     }

    //     public int getId() {
    //         return id;
    //     }

    //     public Command getCommand() {
    //         return command;
    //     }

    //     public int getLamportTimestamp() {
    //         return lamportTimestamp;
    //     }

    //     public String getRemoteFileName() {
    //         return remoteFileName;
    //     }

    //     public String getContent() {
    //         return content;
    //     }
    // }
}
