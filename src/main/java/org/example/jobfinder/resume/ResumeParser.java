package org.example.jobfinder.resume;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.jobfinder.match.Tokenizer;

import java.io.IOException;
import java.nio.file.Path;

public final class ResumeParser {

    private static final String SKILLS_HEADING = "Skills";
    private static final String SKILLS_END_HEADING = "Work Experience";

    private ResumeParser() {
    }

    public static ResumeProfile parse(Path resumePath) throws IOException {
        try (PDDocument document = PDDocument.load(resumePath.toFile())) {
            String text = new PDFTextStripper().getText(document);
            String skillsSection = extractSkillsSection(text);
            return new ResumeProfile(text, Tokenizer.normalize(skillsSection));
        }
    }

    // Scoring matches job postings against the resume's stated skills, not the whole document
    // (work history prose, company names, cities) -- see extractSkillsSection's callers.
    private static String extractSkillsSection(String rawText) {
        StringBuilder section = new StringBuilder();
        boolean inSection = false;
        for (String line : rawText.split("\\r?\\n")) {
            String trimmed = line.strip();
            if (!inSection && trimmed.equalsIgnoreCase(SKILLS_HEADING)) {
                inSection = true;
                continue;
            }
            if (inSection && trimmed.equalsIgnoreCase(SKILLS_END_HEADING)) {
                break;
            }
            if (inSection) {
                section.append(trimmed).append(' ');
            }
        }
        if (section.isEmpty()) {
            throw new IllegalStateException("Could not find a \"" + SKILLS_HEADING + "\" section (ending at \""
                    + SKILLS_END_HEADING + "\") in the resume. Update ResumeParser if the resume's layout changed.");
        }
        return section.toString();
    }
}
