---
name: apply-jobs
description: Run the JobFinder daily job-search-and-apply workflow - search portals for new matching jobs, review staged matches with the user, and submit applications only for the ones they approve. Use when the user asks to run/check JobFinder, find new jobs, or apply to jobs today.
---

# Apply Jobs (JobFinder daily run)

This skill drives JobFinder's search -> review -> approve -> apply pipeline for one sitting. It never submits an application without the user explicitly picking which staged job IDs to approve — that's a deliberate design constraint of this project (see `C:\Users\kriti\.claude\plans\idempotent-singing-goose.md`), not something to change without the user asking.

Run all commands from the repo root (`C:\Users\kriti\Desktop\projects\JobFinder`).

## Steps

1. **Check config exists.** If `config/jobfinder.properties` is missing, stop and tell the user to copy `config/jobfinder.properties.example` to `config/jobfinder.properties`, fill in their search titles/locations, and make sure they've logged into the enabled portals (Indeed/Naukri by default) once in the dedicated Chrome profile referenced by `chrome.profileDir`. Don't proceed until it exists.

2. **Run search.** Execute:
   ```
   ./gradlew run --args="search"
   ```
   This opens a real (non-headless) Chrome window using the user's dedicated profile and scans every portal listed in `portals.enabled`. Show the per-portal summary output (found/staged/below-threshold/already-seen counts) to the user. The CSV store at `store.csvPath` means jobs already seen in a previous day's run are automatically skipped — that's expected, not a bug.

3. **Run list.** Execute:
   ```
   ./gradlew run --args="list"
   ```
   Show the full staged list to the user: id, match %, title, company, portal, url, and matched keywords.

4. **Get explicit approval.** Ask the user which staged job IDs (if any) they want to apply to today — do not assume "all of them." If the list is short, use AskUserQuestion with the option to select specific ids or "none right now." If nothing is staged, or the user picks none, stop here.

5. **Run apply for approved IDs only.** Execute:
   ```
   ./gradlew run --args="apply --ids <comma-separated ids the user approved>"
   ```
   This reuses the same visible Chrome window/profile. Note for the user: only Indeed and Naukri are fully wired up; the apply() calls for both currently click the portal's Apply button and then report back that the multi-step application wizard needs manual completion — watch the browser and finish the submission by hand when prompted. Report each job's outcome (applied / needs manual completion / failed with reason) back to the user.

6. **Summarize.** One short summary: how many new jobs staged today, how many applied, how many still awaiting the user's review (`list` again if they want to see what's left).

## Notes

- Never expand `portals.enabled` or flip a portal from "scaffolded" to "default enabled" as part of running this skill — that's a deliberate scope decision the user made, not something to silently change.
- If `search` or `apply` errors because a portal's selectors don't match the live DOM (zero results, exceptions), report that clearly rather than retrying blindly — those selectors are best-effort and may need the user's input to fix (see the portal's class in `src/main/java/org/example/jobfinder/portal/impl/`).
