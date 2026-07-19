package org.example.jobfinder.cli;

import org.example.jobfinder.config.JobFinderConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JobFinderCli {

    private static final Path CONFIG_PATH = Path.of("config/jobfinder.properties");

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Missing " + CONFIG_PATH + ". Copy config/jobfinder.properties.example to "
                    + CONFIG_PATH + " and fill in your own values.");
            System.exit(1);
        }

        try {
            JobFinderConfig config = JobFinderConfig.load(CONFIG_PATH);
            switch (args[0]) {
                case "search" -> SearchCommand.run(config);
                case "list" -> ListCommand.run(config);
                case "apply" -> ApplyCommand.run(config, parseIds(args));
                default -> printUsage();
            }
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static List<Integer> parseIds(String[] args) {
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--ids")) {
                List<Integer> ids = new ArrayList<>();
                for (String part : args[i + 1].split(",")) {
                    ids.add(Integer.parseInt(part.trim()));
                }
                return ids;
            }
        }
        return List.of();
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  search              Scan enabled portals, stage jobs matching your resume
                  list                Show staged jobs awaiting your approval
                  apply --ids 3,7,12  Submit applications for these specific approved job IDs
                """);
    }
}
