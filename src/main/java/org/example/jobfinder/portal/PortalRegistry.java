package org.example.jobfinder.portal;

import org.example.jobfinder.portal.impl.IndeedPortal;
import org.example.jobfinder.portal.impl.InstahyrePortal;
import org.example.jobfinder.portal.impl.LinkedInPortal;
import org.example.jobfinder.portal.impl.MonsterPortal;
import org.example.jobfinder.portal.impl.NaukriPortal;
import org.example.jobfinder.portal.impl.WellfoundPortal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PortalRegistry {

    private static final Map<String, JobPortal> ALL_PORTALS = buildAll();

    private static Map<String, JobPortal> buildAll() {
        Map<String, JobPortal> portals = new LinkedHashMap<>();
        for (JobPortal portal : List.of(
                new IndeedPortal(),
                new NaukriPortal(),
                new WellfoundPortal(),
                new InstahyrePortal(),
                new LinkedInPortal(),
                new MonsterPortal())) {
            portals.put(portal.name(), portal);
        }
        return portals;
    }

    private PortalRegistry() {
    }

    public static List<JobPortal> resolve(List<String> enabledNames) {
        return enabledNames.stream()
                .map(PortalRegistry::resolveOne)
                .toList();
    }

    public static JobPortal resolveOne(String name) {
        JobPortal portal = ALL_PORTALS.get(name);
        if (portal == null) {
            throw new IllegalArgumentException("Unknown portal: " + name
                    + " (known portals: " + ALL_PORTALS.keySet() + ")");
        }
        return portal;
    }
}
