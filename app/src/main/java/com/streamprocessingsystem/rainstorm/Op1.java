package com.streamprocessingsystem.rainstorm;

import java.util.Scanner;

public class Op1 {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java Op1 <pattern>");
            return;
        }
        String pattern = args[0];
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                String[] parts = line.split(":", 2);
                if (parts.length < 2) {
                    System.err.println("Invalid input format: " + line);
                    continue;
                }
                String tupleId = parts[0].trim();
                String content = parts[1].trim();
                if (content.contains(pattern)) {
                    String[] contentParts = content.split(";");
                    String objectId = contentParts[2].trim();
                    String signType = contentParts[3].trim();
                    System.out.println(tupleId + ";" + objectId + ";" + signType + ";" + line);
                }
            }
        }
    }
}
