---
name: apply-jobs-europe
description: Run the JobFinder LinkedIn job-search-and-apply workflow scoped to Europe (and Japan)-based/remote roles - search LinkedIn, judge fit against the EU resume, and auto-apply via Easy Apply when the match is good and all screening questions can be answered. Use when the user asks to run/check Europe job search, find new Europe jobs, or apply to Europe/Japan jobs today.
---

# Apply Jobs - Europe

This skill is the Europe-only counterpart of the JobFinder pipeline (see also `apply-jobs-india`).
It always searches with `config/config.europe.json` and always uses the EU resume and `--region
eu` -- there is no per-job region decision here, unlike the original combined skill. Its search
locations also include Japan-based roles, which use the same EU resume/answers per prior
convention (content is identical between resume variants; only contact details differ). It applies
automatically once a job clears the match threshold and every screening question has an answer —
it does not stop for per-job approval. That auto-apply behavior was an explicit choice the user
made (accepting LinkedIn ToS/account-risk tradeoffs); don't add a manual approval gate back in
without the user asking.

Run all commands from the repo root (`C:\Users\kriti\Desktop\projects\JobFinder`). The underlying
`scripts/linkedin.py` is shared with `apply-jobs-india` — only the config/resume/region differ.

## Setup (check every run, only act if missing)

1. `config/config.europe.json` must exist — if missing, tell the user to copy
   `config/config.europe.json.example` to `config/config.europe.json`, fill in search
   titles/locations/resume path, and make sure they've logged into LinkedIn by hand once in the
   dedicated Chrome profile at `chrome.profileDir`. Don't proceed without it.
2. Playwright must be installed — run `python -m playwright --version`. If it fails, tell the user
   to run (one-time): `pip install -r scripts/requirements.txt` then `playwright install chrome`.
   Don't proceed without it.

## Steps

1. **Compute a run timestamp** once, e.g. `date +%Y-%m-%d_%H%M%S`. Both log files for this run are
   `data/logs/<timestamp>_applied.jsonl` and `data/logs/<timestamp>_skipped.jsonl` — created lazily
   (first append) via Bash heredocs, e.g.:
   ```
   cat >> data/logs/<timestamp>_applied.jsonl <<'EOF'
   {"jobId": "...", "title": "...", ...}
   EOF
   ```
   Always use a quoted heredoc delimiter (`<<'EOF'`) so the JSON content isn't shell-expanded.
   `data/logs/`, `data/answers.json`, and `data/seen_jobs.json` are shared with `apply-jobs-india`
   — a job already seen via the India skill (unlikely given disjoint search locations, but
   possible) is still skipped correctly, and reusable answers learned by either skill are
   available to both.

2. **Search.** Read `config/config.europe.json` for `search.titles`, `search.locations`, and
   `chrome.profileDir`, then run:
   ```
   python scripts/linkedin.py search --config config/config.europe.json --seen data/seen_jobs.json --run-timestamp <timestamp>
   ```
   This opens a real (non-headless) Chrome window on the dedicated profile and prints a JSON array
   of **new** postings (jobs already in `data/seen_jobs.json` are skipped automatically) — each
   with id, title, company, location, url, and full description text.

3. **For each job returned, in order:**
   a. Read the resume at `resume/Kritika_Goyal_Resume_EU.pdf` via the `Read` tool (once per run,
      reuse across jobs) and compare against the job's description. Judge a 0-100% match with
      brief reasoning (which required skills are present/missing) — this is Claude's own semantic
      judgment, not a keyword-overlap script.
   b. **Match < `config.europe.json`'s `matchThresholdPercent`:** append a line to `_skipped.jsonl`
      (`jobId, title, company, url, matchPercent, reason: "below threshold", reasoning`); mark
      `seen_jobs.json` so it's not rescored next run; move on — no browser interaction for this job.
   c. **Match >= threshold:** run:
      ```
      python scripts/linkedin.py apply --job-id <id> --job-url <url> --resume-path resume/Kritika_Goyal_Resume_EU.pdf \
        --region eu --profile-dir <chrome.profileDir from config> --answers data/answers.json \
        --answer-pipe <a scratch path, e.g. data/.answer_pipe_<id>.json>
      ```
      via `Bash(run_in_background: true)`, and follow it with `Monitor` (each stdout line is one
      JSON event).

4. **Handle apply events as they arrive:**
   - `step_advanced` — informational, no action needed.
   - `question_pending` (`question`, `field_type`, `options`, `required`) — this is a screening
     question the script's pattern list in `data/answers.json` didn't recognize. Ask the user (use
     `AskUserQuestion` if `options` is a non-empty list; otherwise ask directly in chat). Once
     answered, write the answer to the `--answer-pipe` path as `{"answer": "<their answer>"}` via
     the `Write` tool — the script is polling for that file and will resume once it appears.
     After the job's outcome event arrives, judge whether this Q&A is **generic and reusable**
     (salary expectation, notice period, visa/work permit, relocation, gender/EEO, phone, location,
     years of experience, Japanese-language screening questions, etc.) versus **company-specific**
     (e.g. "why do you want to join Acme?"). If generic, append a new entry to `data/answers.json`
     (same shape as existing entries: `category`, `match_substrings`, `field_type`, `value` or
     `value_by_region`, and optionally `regions: ["eu"]` if the answer is EUR-currency/context-
     specific and would be wrong if reused for an India application) so future runs match it
     automatically without asking again. Company-specific answers are used once and not persisted.
   - `applied` — append a full record to `_applied.jsonl`: jobId, title, company, url, matchPercent,
     reasoning, resumeUsed, and every question asked during this application with its answer and
     whether it came from `answers.json` or was newly asked. Then run
     `python scripts/linkedin.py record-outcome --job-id <id> --status applied --seen data/seen_jobs.json --run-timestamp <timestamp>`.
   - `error` (`reason`) — selector mismatch, closed listing, external-apply-only, interested-only
     listing, no Easy Apply button, exceeded step limit, or the user declined/timed out on a
     question. Append to `_skipped.jsonl` with the reason. Do **not** mark it in `seen_jobs.json`
     (unlike below-threshold skips) — leave it eligible for retry next run in case selectors or
     answers improve. Report the error clearly to the user rather than retrying blindly; if it
     looks like a LinkedIn DOM/selector change, point at `scripts/linkedin.py` (LinkedIn changes
     markup periodically — the selectors are best-effort and may need recalibration).

5. **Summarize.** One short summary: jobs found, skipped below threshold, applied, failed —
   plus the paths to today's two log files.

## Notes

- Never re-introduce a per-job manual-approval gate, and never re-enable other portals
  (Indeed/Naukri/etc.) as part of running this skill — LinkedIn-only auto-apply is a deliberate
  scope decision the user made, not something to silently expand.
- Never search India locations or use the IN resume from this skill — that's what
  `apply-jobs-india` is for. If the user wants both regions run in one sitting, run both skills
  in sequence, not by broadening this skill's config.
- `data/answers.json` is the growing memory of reusable screening-question answers, shared across
  both region skills — treat it as precious. Only add entries there for genuinely reusable answers;
  keep company-specific answers out of it, and scope currency/region-specific answers (salary
  figures) with a `regions` key so they can't leak onto the wrong region's applications.
- If Japan-specific listings surface unusual screening questions (JLPT level, rirekisho/shokumu-
  keirekisho documents, Japanese-interview comfort), check `data/answers.json` first — several such
  patterns already exist there from prior runs.
- If `search` or `apply` come back empty/wrong repeatedly, LinkedIn's DOM likely changed — say so
  plainly rather than guessing new selectors blind; the user can watch the Chrome window (it runs
  visibly, not headless) to help diagnose.
