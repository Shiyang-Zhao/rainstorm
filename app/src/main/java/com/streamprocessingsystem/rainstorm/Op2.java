package com.streamprocessingsystem.rainstorm;

import java.util.Scanner;

public class Op2 {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java Op2 <SignPostType>");
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
                String[] subParts = content.split(";");
                String signPostType = subParts[6].trim();
                String category = subParts[8].trim();

                if (signPostType.equals(pattern)) {
                    System.out.println(tupleId + ";" + category);
                }
            }
        }
    }
}
