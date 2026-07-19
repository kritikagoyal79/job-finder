package org.example.jobfinder.portal.impl;

import org.example.jobfinder.config.JobFinderConfig;
import org.example.jobfinder.match.JobListing;
import org.example.jobfinder.portal.ApplyResult;
import org.example.jobfinder.portal.JobPortal;
import org.example.jobfinder.portal.SearchCriteria;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NOT enabled by default -- LinkedIn has aggressive anti-bot detection (login walls, captchas,
 * "unusual activity" challenges) and its Terms of Service prohibit automated scraping/apply.
 * Treat this as experimental: run `search` manually first, watch the browser window for
 * challenge pages, and expect to hit blocks that plain selector fixes won't solve.
 *
 * Selectors last calibrated 2026-07-14 against an authenticated session (LinkedIn serves a
 * different, selector-incompatible logged-out page). Search result cards don't carry a job
 * description snippet the way Naukri's do, so search() opens each job's own page individually
 * to fetch real description text for skill matching -- meaning one extra page load per job
 * (up to maxResultsPerLocation per location) on top of the search page loads themselves.
 */
public final class LinkedInPortal implements JobPortal {

    // Matched against JobListing.location() (case-insensitively) to pick the .in vs .eu config
    // values -- see config/jobfinder.properties. Anything that doesn't hit one of these (Germany,
    // Netherlands, Ireland, Japan, "Remote", etc.) falls back to .eu.
    private static final Set<String> INDIA_LOCATION_HINTS = Set.of(
            "india", "bangalore", "bengaluru", "delhi", "mumbai", "hyderabad", "pune", "chennai",
            "gurgaon", "gurugram", "noida", "kolkata");

    private static final String PHONE_HINT = "phone";
    private static final String LOCATION_HINT = "location";
    private static final String NOTICE_HINT = "notice period";
    private static final int MAX_EASY_APPLY_STEPS = 8;


    @Override
    public String name() {
        return "linkedin";
    }

    @Override
    public List<JobListing> search(SearchCriteria criteria, WebDriver driver) {
        List<JobListing> listings = new ArrayList<>();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        // Tracked per location (not a single shared total) so a job-dense location searched first
        // (e.g. India) can't exhaust the whole budget before other locations get evaluated.
        Map<String, Integer> countPerLocation = new HashMap<>();

        for (String title : criteria.titles()) {
            for (String location : criteria.locations()) {
                String url = "https://www.linkedin.com/jobs/search/?keywords="
                        + URLEncoder.encode(title, StandardCharsets.UTF_8)
                        + "&location=" + URLEncoder.encode(location, StandardCharsets.UTF_8);
                driver.get(url);
                wait.until(d -> !d.findElements(By.cssSelector("li[data-occludable-job-id]")).isEmpty()
                        || !d.findElements(By.cssSelector("div.jobs-search-no-results-banner")).isEmpty());
                // LinkedIn's job list is virtualized and keeps re-rendering for ~1s after the first
                // card appears (see class "occludable-update"); grabbing elements too early makes
                // every one of them StaleElementReferenceException as soon as we read from it.
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return listings;
                }

                JavascriptExecutor js = (JavascriptExecutor) driver;
                List<WebElement> cards = driver.findElements(By.cssSelector("li[data-occludable-job-id]"));
                for (WebElement card : cards) {
                    if (countPerLocation.getOrDefault(location, 0) >= criteria.maxResultsPerLocation()) {
                        break;
                    }
                    try {
                        // The card list is virtualized ("occludable"): cards outside the viewport
                        // stay as unhydrated skeletons with no title/company/location text until
                        // scrolled into view.
                        js.executeScript("arguments[0].scrollIntoView({block: 'center'});", card);
                        Thread.sleep(300);

                        String externalId = card.getAttribute("data-occludable-job-id");
                        String jobTitle = safeText(card, By.cssSelector("a.job-card-list__title--link strong"));
                        String company = safeText(card, By.cssSelector("div.artdeco-entity-lockup__subtitle"));
                        String jobLocation = safeText(card, By.cssSelector("div.artdeco-entity-lockup__caption"));
                        String jobUrl = "https://www.linkedin.com/jobs/view/" + externalId + "/";

                        listings.add(new JobListing("linkedin", externalId, jobTitle, company, jobLocation, jobUrl, jobTitle));
                        countPerLocation.merge(location, 1, Integer::sum);
                    } catch (Exception e) {
                        // One malformed card shouldn't abort the whole search; skip it.
                    }
                }
            }
        }

        for (int i = 0; i < listings.size(); i++) {
            listings.set(i, withFullDescription(listings.get(i), driver, wait));
        }
        return listings;
    }

    // Falls back to the title-only listing (already a reasonable description placeholder) if the
    // job's own page doesn't load the expected heading -- a closed/removed listing, a challenge
    // page, or wording LinkedIn changed since this was calibrated.
    private JobListing withFullDescription(JobListing job, WebDriver driver, WebDriverWait wait) {
        try {
            driver.get(job.url());
            wait.until(d -> !d.findElements(By.xpath("//h2[contains(text(),'About the job')]")).isEmpty());
            WebElement heading = driver.findElement(By.xpath("//h2[contains(text(),'About the job')]"));
            String description = heading.findElement(By.xpath("../../..")).getText();
            Thread.sleep(1000);
            return new JobListing(job.portal(), job.externalId(), job.title(), job.company(), job.location(),
                    job.url(), description);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return job;
        } catch (Exception e) {
            return job;
        }
    }

    // Walks LinkedIn's multi-step Easy Apply modal (validated live against job #58, Quince
    // Staff Engineer, on 2026-07-15 -- see DiagnoseLinkedInApplyPagesTest for the raw page dump
    // this was calibrated from). Only fills fields it recognizes (phone, current location,
    // notice period, resume selection/upload) from config/jobfinder.properties; any other
    // required question it can't confidently answer aborts the application rather than guessing,
    // per explicit decision to never submit a guessed answer on a real application.
    @Override
    public ApplyResult apply(JobListing job, WebDriver driver, JobFinderConfig config) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        driver.get(job.url());
        sleep(1500);

        if (!driver.findElements(By.xpath("//*[contains(text(),'No longer accepting applications')]")).isEmpty()) {
            return new ApplyResult(false, "Listing is closed (no longer accepting applications).");
        }

        List<WebElement> easyApplyButtons = driver.findElements(By.xpath("//button[contains(@aria-label,'Easy Apply')]"));
        if (easyApplyButtons.isEmpty()) {
            return new ApplyResult(false, "No Easy Apply button on this job -- it requires applying on the employer's own site.");
        }
        click(driver, easyApplyButtons.get(0));
        sleep(1500);

        String region = regionFor(job);
        String resumePath = config.resumePath(region).toAbsolutePath().normalize().toString();
        Map<String, String> knownValues = Map.of(
                PHONE_HINT, config.applyPhone(region),
                LOCATION_HINT, config.applyCurrentLocation(region),
                NOTICE_HINT, config.applyNoticePeriod(region));

        try {
            for (int step = 0; step < MAX_EASY_APPLY_STEPS; step++) {
                String blocker = fillKnownFields(driver, knownValues, resumePath, config);
                if (blocker != null) {
                    dumpDiagnostics(driver);
                    pauseForObservation();
                    discard(driver);
                    return new ApplyResult(false, "Unhandled Easy Apply question: \"" + blocker + "\" -- complete manually.");
                }

                List<WebElement> submitButtons = driver.findElements(By.xpath(
                        "//button[contains(@aria-label,'Submit application') or normalize-space(.)='Submit application'"
                        + " or normalize-space(.)='Submit']"));
                if (!submitButtons.isEmpty()) {
                    click(driver, submitButtons.get(0));
                    sleep(2000);
                    return new ApplyResult(true, "Submitted via LinkedIn Easy Apply.");
                }

                List<WebElement> nextButtons = driver.findElements(By.xpath(
                        "//button[contains(@aria-label,'Continue to next step') or contains(@aria-label,'Review your application')"
                        + " or normalize-space(.)='Next' or normalize-space(.)='Review' or normalize-space(.)='Continue']"));
                if (nextButtons.isEmpty()) {
                    dumpDiagnostics(driver);
                    pauseForObservation();
                    discard(driver);
                    return new ApplyResult(false, "Easy Apply reached an unrecognized step with no Next/Review/Submit button.");
                }
                String validationError = clickAndCheckForValidationError(driver, wait, nextButtons.get(0));
                if (validationError != null) {
                    // Some steps only render their actual requirement (e.g. an "Upload resume"
                    // prompt) once a failed submit attempt reveals it. Re-scan and retry once
                    // before giving up, in case fillKnownFields can now see and resolve it.
                    String recoveryBlocker = fillKnownFields(driver, knownValues, resumePath, config);
                    if (recoveryBlocker != null) {
                        validationError = "Unhandled Easy Apply question revealed after a failed step: \""
                                + recoveryBlocker + "\"";
                    } else {
                        List<WebElement> retryNextButtons = driver.findElements(By.xpath(
                                "//button[contains(@aria-label,'Continue to next step') or contains(@aria-label,'Review your application')"
                                        + " or normalize-space(.)='Next' or normalize-space(.)='Review' or normalize-space(.)='Continue']"));
                        validationError = retryNextButtons.isEmpty()
                                ? "no Next/Review button after retry"
                                : clickAndCheckForValidationError(driver, wait, retryNextButtons.get(0));
                    }
                }
                if (validationError != null) {
                    dumpDiagnostics(driver);
                    pauseForObservation();
                    discard(driver);
                    return new ApplyResult(false, "Easy Apply blocked progress: " + validationError);
                }
                // stalenessOf() only confirms the old step's content detached, not that the new
                // step has finished rendering -- fillKnownFields() at the top of the next iteration
                // was observed querying for a button that existed moments later but not yet here.
                sleep(3000);
            }
            pauseForObservation();
            discard(driver);
            return new ApplyResult(false,
                    "Easy Apply exceeded " + MAX_EASY_APPLY_STEPS + " steps; aborting rather than guessing further.");
        } catch (Exception e) {
            // Selenium exceptions routinely embed a multi-line diagnostic dump (Session/Build/
            // Driver info) in getMessage() -- collapse to one line for a readable console/CSV
            // message (JobStore.markFailed() also guards against this, but keep it clean here too).
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            return new ApplyResult(false, "Easy Apply flow failed: " + reason.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim());
        }
    }

    private String regionFor(JobListing job) {
        String location = job.location() == null ? "" : job.location().toLowerCase(Locale.ROOT);
        for (String hint : INDIA_LOCATION_HINTS) {
            if (location.contains(hint)) {
                return "in";
            }
        }
        return "eu";
    }

    // Fills every field on the current step it recognizes. LinkedIn doesn't use a stable wrapper
    // class for form fields (ids are React-generated, e.g. ":r6:"), so fields are matched via each
    // <label for="..."> to its input rather than a container selector. Returns the label of the
    // first field it can't answer (still empty/unselected, no known-value match), or null if the
    // step is clear to advance from.
    private String fillKnownFields(WebDriver driver, Map<String, String> knownValues, String resumePath,
            JobFinderConfig config) {
        selectOrUploadResume(driver, resumePath);

        for (WebElement label : driver.findElements(By.cssSelector("label[for]"))) {
            String labelText = label.getText().trim();
            if (labelText.isEmpty()) {
                continue;
            }
            WebElement input = fieldFor(driver, label);
            if (input == null || !input.isDisplayed()) {
                continue;
            }
            String lowerLabel = labelText.toLowerCase(Locale.ROOT);
            if (lowerLabel.contains("resume")) {
                continue;
            }

            if ("select".equals(input.getTagName())) {
                String selectedText = new Select(input).getFirstSelectedOption().getText().trim();
                if (!selectedText.isEmpty() && !selectedText.equalsIgnoreCase("Select an option")) {
                    continue;
                }
                return labelText; // dropdown answers vary too much per job to guess safely
            }

            String type = input.getAttribute("type");
            if ("radio".equals(type) || "checkbox".equals(type) || "file".equals(type)) {
                continue; // radios/checkboxes are handled per-group below; file is a resume upload
            }

            String existing = input.getAttribute("value");
            if (existing != null && !existing.isBlank()) {
                continue;
            }
            String value = matchKnownValue(lowerLabel, knownValues);
            if (value == null) {
                return labelText;
            }
            click(driver, input);
            input.clear();
            input.sendKeys(value);
            sleep(400);
            if (lowerLabel.contains(LOCATION_HINT) || lowerLabel.contains("city")) {
                selectFirstTypeaheadSuggestion(driver);
            }
        }

        String ariaBlocker = fillKnownAriaLabelFields(driver, config);
        if (ariaBlocker != null) {
            return ariaBlocker;
        }

        return firstUnansweredRadioGroup(driver, config);
    }

    // Some fields carry their question directly as aria-label on the input rather than via a
    // separate <label for="...">, e.g. "What is your Japanese language proficiency?" -- invisible
    // to the label[for] scan above. Only blocks on these if actually required, since aria-label is
    // used far more broadly than <label for> and would otherwise flag harmless decorative/optional
    // inputs (LinkedIn's own site-wide search box has no aria-label and is unaffected either way).
    private String fillKnownAriaLabelFields(WebDriver driver, JobFinderConfig config) {
        for (WebElement input : driver.findElements(By.cssSelector("input[aria-label], textarea[aria-label]"))) {
            if (!input.isDisplayed()) {
                continue;
            }
            String ariaLabel = input.getAttribute("aria-label");
            if (ariaLabel == null || ariaLabel.isBlank()) {
                continue;
            }
            String existing = input.getAttribute("value");
            if (existing != null && !existing.isBlank()) {
                continue;
            }
            boolean required = "true".equals(input.getAttribute("required")) || "true".equals(input.getAttribute("aria-required"));
            if (!required) {
                continue;
            }
            String lowerAria = ariaLabel.toLowerCase(Locale.ROOT);
            String value = lowerAria.contains("japanese") && (lowerAria.contains("proficiency") || lowerAria.contains("jlpt"))
                    ? config.optionalProperty("apply.japaneseProficiencyLevel")
                    : null;
            if (value == null) {
                return ariaLabel;
            }
            click(driver, input);
            input.clear();
            input.sendKeys(value);
            sleep(400);
        }
        return null;
    }

    private WebElement fieldFor(WebDriver driver, WebElement label) {
        String forId = label.getAttribute("for");
        if (forId == null || forId.isBlank()) {
            return null;
        }
        List<WebElement> matches = driver.findElements(By.id(forId));
        return matches.isEmpty() ? null : matches.get(0);
    }

    // Radio/checkbox questions (yes/no, work authorization, etc.) are grouped under a <fieldset>
    // with a <legend> naming the question rather than a per-input <label for="...">. A handful of
    // these recur often enough across this job search (relocation, visa sponsorship, interview
    // language comfort) to answer from config; anything else still blocks rather than guessing.
    //
    // Clicking one fieldset's option was observed to re-render the whole step, staling every other
    // fieldset WebElement already fetched in the same pass -- so after each successful answer, this
    // breaks out and re-fetches the fieldset list from scratch rather than continuing to iterate a
    // now-stale snapshot. A stray staleness exception mid-pass is treated the same way.
    private String firstUnansweredRadioGroup(WebDriver driver, JobFinderConfig config) {
        for (int pass = 0; pass < 20; pass++) {
            boolean answeredOneThisPass = false;
            try {
                for (WebElement fieldset : driver.findElements(By.cssSelector("fieldset"))) {
                    List<WebElement> options = fieldset.findElements(By.cssSelector("input[type='radio'], input[type='checkbox']"));
                    if (options.isEmpty() || options.stream().anyMatch(WebElement::isSelected)) {
                        continue;
                    }
                    String question = questionTextFor(driver, fieldset);
                    String lowerQuestion = question.toLowerCase(Locale.ROOT);
                    String knownAnswer = knownYesNoAnswer(config, lowerQuestion);
                    if (knownAnswer != null && answerYesNo(driver, fieldset, knownAnswer)) {
                        sleep(300);
                        answeredOneThisPass = true;
                        break;
                    }
                    return question.isEmpty() ? "unlabeled radio/checkbox question" : question;
                }
            } catch (StaleElementReferenceException e) {
                answeredOneThisPass = true; // DOM changed underneath us -- re-fetch and retry
            }
            if (!answeredOneThisPass) {
                return null; // full pass with nothing left to answer and no blocker hit
            }
        }
        return null;
    }

    // Only covers screening questions confirmed to recur on this search (see
    // config/jobfinder.properties): interview language comfort, relocation, visa/work-permit
    // sponsorship, and Japan-format resume documents (rirekisho/shokumu-keirekisho). Returns null
    // (never guesses) for anything else, including other yes/no questions like willingness to
    // work weekends or salary expectations.
    private String knownYesNoAnswer(JobFinderConfig config, String lowerQuestion) {
        if (lowerQuestion.contains("japanese") && lowerQuestion.contains("interview")) {
            return config.optionalProperty("apply.comfortableWithJapaneseInterview");
        }
        if (lowerQuestion.contains("relocate")) {
            return config.optionalProperty("apply.openToRelocate");
        }
        if (lowerQuestion.contains("履歴書") || lowerQuestion.contains("職務経歴書")
                || lowerQuestion.contains("rirekisho") || lowerQuestion.contains("shokumu")) {
            return config.optionalProperty("apply.canSubmitJapaneseResumeDocs");
        }
        if (lowerQuestion.contains("visa") || lowerQuestion.contains("sponsorship")
                || lowerQuestion.contains("work permit") || lowerQuestion.contains("work authorization")) {
            return config.optionalProperty("apply.needVisaSponsorship");
        }
        return null;
    }

    // Matches each radio/checkbox's own value attribute first (some LinkedIn variants set
    // value="Yes"/"No"), then falls back to its associated <label for="..."> text. Observed live
    // that neither holds for this account/variant -- both options have value="on" (the HTML
    // default for an unset value attribute) and an empty linked label -- so as a last resort, with
    // exactly two options, picks by position: the fieldset's own text consistently rendered
    // "Yes" before "No", so index 0 = Yes, index 1 = No.
    private boolean answerYesNo(WebDriver driver, WebElement fieldset, String desiredAnswer) {
        if (desiredAnswer == null || desiredAnswer.isBlank()) {
            return false;
        }
        String want = desiredAnswer.trim().toLowerCase(Locale.ROOT);
        List<WebElement> options = fieldset.findElements(By.cssSelector("input[type='radio'], input[type='checkbox']"));
        for (WebElement option : options) {
            String value = option.getAttribute("value");
            if (value != null && value.trim().toLowerCase(Locale.ROOT).equals(want)) {
                jsClick(driver, option);
                return true;
            }
        }
        for (WebElement option : options) {
            String optionId = option.getAttribute("id");
            if (optionId == null || optionId.isBlank()) {
                continue;
            }
            for (WebElement label : driver.findElements(By.cssSelector("label[for]"))) {
                if (optionId.equals(label.getAttribute("for"))
                        && label.getText().trim().toLowerCase(Locale.ROOT).equals(want)) {
                    jsClick(driver, option);
                    return true;
                }
            }
        }
        if (options.size() == 2 && (want.equals("yes") || want.equals("no"))) {
            jsClick(driver, options.get(want.equals("yes") ? 0 : 1));
            return true;
        }
        return false;
    }

    // Custom-styled radio/checkbox inputs (as seen here: LinkedIn's Yes/No options render a
    // visually hidden native input behind a styled label) fail WebDriver's normal .click()
    // interactability check. Dispatching the click via JS bypasses that check safely -- it fires
    // the same native click event without requiring the element to be visible/in-viewport.
    private void jsClick(WebDriver driver, WebElement element) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("arguments[0].click();", element);
    }

    // Tries <legend>, then aria-labelledby's referenced element, then the parent container's full
    // text (which usually includes both the question and the Yes/No option labels together) --
    // some LinkedIn variants associate the question with the fieldset by neither of the first two.
    private String questionTextFor(WebDriver driver, WebElement fieldset) {
        List<WebElement> legend = fieldset.findElements(By.cssSelector("legend"));
        if (!legend.isEmpty() && !legend.get(0).getText().isBlank()) {
            return legend.get(0).getText().trim();
        }
        String labelledBy = fieldset.getAttribute("aria-labelledby");
        if (labelledBy != null && !labelledBy.isBlank()) {
            for (String id : labelledBy.split("\\s+")) {
                List<WebElement> referenced = driver.findElements(By.id(id));
                if (!referenced.isEmpty() && !referenced.get(0).getText().isBlank()) {
                    return referenced.get(0).getText().trim();
                }
            }
        }
        try {
            String parentText = fieldset.findElement(By.xpath("..")).getText().trim();
            if (!parentText.isBlank()) {
                return parentText.replace("\n", " ");
            }
        } catch (Exception ignored) {
        }
        return fieldset.getText().trim().replace("\n", " ");
    }

    private String matchKnownValue(String lowerLabel, Map<String, String> knownValues) {
        if (lowerLabel.contains(PHONE_HINT)) {
            return knownValues.get(PHONE_HINT);
        }
        if (lowerLabel.contains(LOCATION_HINT) || lowerLabel.contains("city")) {
            return knownValues.get(LOCATION_HINT);
        }
        if (lowerLabel.contains(NOTICE_HINT)) {
            return knownValues.get(NOTICE_HINT);
        }
        return null;
    }

    // Best-effort: selects a previously-uploaded resume card matching our filename if one is
    // shown, otherwise uploads via the file input if this step has one. If neither is present,
    // this step simply isn't a resume step.
    private void selectOrUploadResume(WebDriver driver, String resumePath) {
        String fileName = Path.of(resumePath).getFileName().toString();
        List<WebElement> resumeCards = driver.findElements(
                By.cssSelector("[class*='document-upload'] [class*='file-name']"));
        System.out.println("  [resume] existing resume cards found: " + resumeCards.size());
        for (WebElement card : resumeCards) {
            if (card.getText().trim().equalsIgnoreCase(fileName)) {
                click(driver, card);
                sleep(300);
                return;
            }
        }
        List<WebElement> fileInputs = driver.findElements(By.cssSelector("input[type='file']"));
        System.out.println("  [resume] existing file inputs found: " + fileInputs.size());
        if (!fileInputs.isEmpty()) {
            fileInputs.get(0).sendKeys(resumePath);
            sleep(2000);
            return;
        }

        // No file input exists in the DOM yet on this step -- LinkedIn only reveals one once the
        // "Upload resume" trigger is clicked. sendKeys() on a file input never opens an OS dialog
        // (Selenium sets the value directly), so this stays script-safe as long as we never click
        // the input itself -- only this trigger button, which on some LinkedIn variants is wired
        // straight to a native file picker instead of revealing an input. Submit is still gated on
        // every field resolving cleanly first, so nothing gets submitted even if that happens.
        // Filters in Java on WebElement.getText() (the rendered/visible text, same mechanism
        // dumpDiagnostics() uses and confirmed reliable) rather than XPath's string-value of the
        // node, which computes over raw DOM text and didn't reliably match here.
        List<WebElement> uploadButtons = driver.findElements(By.cssSelector("button")).stream()
                .filter(b -> {
                    try {
                        String text = b.getText().toLowerCase(Locale.ROOT);
                        String aria = b.getAttribute("aria-label");
                        String ariaLower = aria == null ? "" : aria.toLowerCase(Locale.ROOT);
                        return (text.contains("upload") && text.contains("resume"))
                                || (ariaLower.contains("upload") && ariaLower.contains("resume"));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
        System.out.println("  [resume] upload-trigger buttons found: " + uploadButtons.size());
        if (uploadButtons.isEmpty()) {
            return;
        }
        click(driver, uploadButtons.get(0));
        System.out.println("  [resume] clicked upload-trigger button");
        sleep(1000);
        List<WebElement> revealedInputs = driver.findElements(By.cssSelector("input[type='file']"));
        System.out.println("  [resume] file inputs found after click: " + revealedInputs.size());
        if (!revealedInputs.isEmpty()) {
            revealedInputs.get(0).sendKeys(resumePath);
            System.out.println("  [resume] sent resume path to file input: " + resumePath);
            sleep(2000);
        }
    }

    private void selectFirstTypeaheadSuggestion(WebDriver driver) {
        List<WebElement> options = driver.findElements(By.cssSelector("div[role='listbox'] div[role='option']"));
        if (!options.isEmpty()) {
            click(driver, options.get(0));
        }
    }

    // Clicks Next/Review and distinguishes "advanced to the next step" from "LinkedIn rejected
    // the step" by waiting for the clicked button to go stale; if it's still there, the step
    // didn't advance and we surface whatever inline validation message LinkedIn is showing.
    private String clickAndCheckForValidationError(WebDriver driver, WebDriverWait wait, WebElement button) {
        click(driver, button);
        try {
            wait.until(ExpectedConditions.stalenessOf(button));
            return null;
        } catch (TimeoutException e) {
            return driver.findElements(By.cssSelector("div.artdeco-inline-feedback--error, span[class*='error-message']"))
                    .stream()
                    .map(WebElement::getText)
                    .filter(t -> !t.isBlank())
                    .findFirst()
                    .orElse("form did not advance (unrecognized validation issue)");
        }
    }

    // Closes the Easy Apply modal without submitting, discarding the in-progress draft LinkedIn
    // would otherwise save. Best-effort cleanup -- a modal left open doesn't block the next job's
    // apply() call, since that navigates to a fresh URL anyway.
    private void discard(WebDriver driver) {
        try {
            List<WebElement> dismiss = driver.findElements(By.cssSelector("button[aria-label='Dismiss']"));
            if (dismiss.isEmpty()) {
                return;
            }
            click(driver, dismiss.get(0));
            sleep(500);
            List<WebElement> discardBtn = driver.findElements(By.xpath("//button[contains(text(),'Discard')]"));
            if (!discardBtn.isEmpty()) {
                click(driver, discardBtn.get(0));
            }
        } catch (Exception ignored) {
            // Best-effort only.
        }
    }

    private void click(WebDriver driver, WebElement element) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("arguments[0].scrollIntoView({block: 'center'});", element);
        element.click();
    }

    // Captures whatever labels/buttons/inputs are actually visible in the modal at the point
    // apply() gave up, so selectors can be recalibrated from real DOM data instead of guesswork.
    // Written to a UTF-8 file (in addition to stdout) because the Windows console's codepage
    // garbles non-ASCII question text (e.g. Japanese) that stdout can't render correctly.
    private void dumpDiagnostics(WebDriver driver) {
        StringBuilder out = new StringBuilder();
        out.append("--- Easy Apply diagnostics ---\n");
        for (WebElement button : driver.findElements(By.cssSelector("button"))) {
            try {
                if (!button.isDisplayed()) {
                    continue;
                }
                String aria = button.getAttribute("aria-label");
                String text = button.getText().trim();
                if ((aria != null && !aria.isBlank()) || !text.isBlank()) {
                    out.append("BUTTON aria-label=[").append(aria).append("] text=[").append(text)
                            .append("] enabled=").append(button.isEnabled()).append('\n');
                }
            } catch (Exception ignored) {
            }
        }
        for (WebElement label : driver.findElements(By.cssSelector("label, legend, span.artdeco-text-input--label"))) {
            try {
                String text = label.getText().trim();
                if (!text.isBlank()) {
                    out.append("LABEL for=[").append(label.getAttribute("for")).append("]: ").append(text).append('\n');
                }
            } catch (Exception ignored) {
            }
        }
        for (WebElement input : driver.findElements(By.cssSelector("input, select, textarea"))) {
            try {
                if (!input.isDisplayed()) {
                    continue;
                }
                out.append("INPUT tag=").append(input.getTagName()).append(" type=[").append(input.getAttribute("type"))
                        .append("] id=[").append(input.getAttribute("id")).append("] value=[")
                        .append(input.getAttribute("value")).append("] aria-label=[")
                        .append(input.getAttribute("aria-label")).append("] placeholder=[")
                        .append(input.getAttribute("placeholder")).append("] aria-describedby=[")
                        .append(input.getAttribute("aria-describedby")).append("] required=[")
                        .append(input.getAttribute("required")).append("] aria-required=[")
                        .append(input.getAttribute("aria-required")).append("]\n");
                try {
                    WebElement parent = input.findElement(By.xpath(".."));
                    out.append("  (parent text: [").append(parent.getText().trim().replace("\n", " | ")).append("])\n");
                } catch (Exception ignored2) {
                }
            } catch (Exception ignored) {
            }
        }
        for (WebElement fieldset : driver.findElements(By.cssSelector("fieldset"))) {
            try {
                boolean anySelected = fieldset.findElements(By.cssSelector("input[type='radio'], input[type='checkbox']"))
                        .stream().anyMatch(WebElement::isSelected);
                out.append("FIELDSET aria-labelledby=[").append(fieldset.getAttribute("aria-labelledby"))
                        .append("] anySelected=").append(anySelected).append('\n');
                WebElement parent = fieldset.findElement(By.xpath(".."));
                String parentText = parent.getText().trim().replace("\n", " | ");
                out.append("FIELDSET parent text=[").append(parentText).append("]\n");
            } catch (Exception ignored) {
            }
        }
        // Included regardless of isDisplayed() -- file inputs are conventionally styled invisible
        // behind a real button, so the visible-only INPUT dump above would miss them entirely.
        for (WebElement fileInput : driver.findElements(By.cssSelector("input[type='file']"))) {
            try {
                out.append("FILE INPUT id=[").append(fileInput.getAttribute("id")).append("] displayed=")
                        .append(fileInput.isDisplayed()).append(" accept=[").append(fileInput.getAttribute("accept"))
                        .append("]\n");
            } catch (Exception ignored) {
            }
        }
        for (WebElement el : driver.findElements(
                By.cssSelector("[class*='document-upload'], [class*='resume'], [data-test*='resume']"))) {
            try {
                String text = el.getText().trim().replace("\n", " | ");
                out.append("RESUME-ISH tag=").append(el.getTagName()).append(" class=[").append(el.getAttribute("class"))
                        .append("] text=[").append(text).append("]\n");
            } catch (Exception ignored) {
            }
        }
        out.append("--- end diagnostics ---\n");

        System.out.println("  (diagnostics written to data/linkedin-apply-diagnostics.txt)");
        try {
            java.nio.file.Path path = java.nio.file.Path.of("data/linkedin-apply-diagnostics.txt");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("  (failed to write diagnostics file: " + e.getMessage() + ")");
        }
    }

    // Gives the person watching the browser a chance to read the screen before discard() closes
    // the modal, since a blocked/aborted apply would otherwise vanish within a second of failing.
    private void pauseForObservation() {
        System.out.println("  Stuck here -- leaving the screen up for a few seconds before closing...");
        sleep(8000);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeText(WebElement parent, By selector) {
        List<WebElement> elements = parent.findElements(selector);
        return elements.isEmpty() ? "" : elements.get(0).getText();
    }
}
