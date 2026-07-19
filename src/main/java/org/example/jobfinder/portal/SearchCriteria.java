package org.example.jobfinder.portal;

import java.util.List;

public record SearchCriteria(List<String> titles, List<String> locations, int maxResultsPerLocation) {
}
