---
name: apply-jobs-india
description: Run the JobFinder LinkedIn job-search-and-apply workflow scoped to India-based/remote roles - search LinkedIn, judge fit against whatever resume is configured, and auto-apply via Easy Apply when the match is good and all screening questions can be answered. Use when the user asks to run/check India job search, find new India jobs, or apply to India jobs today.
---

# Apply Jobs - India

This skill is the India-only counterpart of the JobFinder pipeline (see also `apply-jobs-europe`).
It always searches with `config/config.india.json` and always uses `--region in` -- there is no
per-job region decision here, unlike the original combined skill. It applies automatically once a
job clears the match threshold and every screening question has an answer — it does not stop for
per-job approval. That auto-apply behavior was an explicit choice the user made (accepting
LinkedIn ToS/account-risk tradeoffs); don't add a manual approval gate back in without the user
asking.

The resume is not hardcoded — `config.india.json`'s `resume` field points at whatever profile is
currently in use, and search titles are derived from that resume fresh each run rather than a
fixed list, so this skill adapts to whoever's resume is there (senior backend engineer today,
junior hardware engineer tomorrow) without editing config by hand.

Run all commands from the repo root (`C:\Users\kriti\Desktop\projects\JobFinder`). The underlying
`scripts/linkedin.py` is shared with `apply-jobs-europe` — only the config/resume/region differ.

## Setup (check every run, only act if missing)

1. `config/config.india.json` must exist — if missing, tell the user to copy
   `config/config.india.json.example` to `config/config.india.json`, fill in locations, resume
   path, and make sure they've logged into LinkedIn by hand once in the dedicated Chrome profile at
   `chrome.profileDir`. Don't proceed without it.
2. The file at `config.india.json`'s `resume` path must exist — if missing or the user says it's
   outdated, ask them to place an up-to-date resume there (or point `resume` at wherever they put
   it) before continuing. This is the one piece of per-person setup the skill can't infer.
3. Playwright must be installed — run `python -m playwright --version`. If it fails, tell the user
   to run (one-time): `pip install -r scripts/requirements.txt` then `playwright install chrome`.
   Don't proceed without it.

## Steps

1. **Read the resume once.** Read `config.india.json`'s `resume` file via the `Read` tool — reuse
   this same reading for every job's match scoring later in the run, and for deriving search terms
   now. From it, judge: current seniority level, core skill area(s)/domain, and 2-4 LinkedIn job
   title search phrases that fit (e.g. a senior backend engineer's resume might yield "Senior
   Software Engineer, Backend Engineer, Java Developer"; a junior hardware engineer's resume might
   yield "Hardware Engineer, Electronics Engineer, Embedded Systems Engineer"). These replace
   `config.india.json`'s static `search.titles` list for this run — that list is only a fallback
   for when the resume can't be read.

2. **Compute a run timestamp** once, e.g. `date +%Y-%m-%d_%H%M%S`. Both log files for this run are
   `data/logs/<timestamp>_applied.jsonl` and `data/logs/<timestamp>_skipped.jsonl` — created lazily
   (first append) via Bash heredocs, e.g.:
   ```
   cat >> data/logs/<timestamp>_applied.jsonl <<'EOF'
   {"jobId": "...", "title": "...", ...}
   EOF
   ```
   Always use a quoted heredoc delimiter (`<<'EOF'`) so the JSON content isn't shell-expanded.
   `data/logs/`, `data/answers.json`, and `data/seen_jobs.json` are shared with `apply-jobs-europe`
   — a job already seen via the Europe skill (unlikely given disjoint search locations, but
   possible) is still skipped correctly, and reusable answers learned by either skill are
   available to both.

3. **Search.** Run:
   ```
   python scripts/linkedin.py search --config config/config.india.json --seen data/seen_jobs.json \
     --run-timestamp <timestamp> --titles "<the titles derived in step 1, comma-separated>"
   ```
   This opens a real (non-headless) Chrome window on the dedicated profile and prints a JSON array
   of **new** postings (jobs already in `data/seen_jobs.json` are skipped automatically) — each
   with id, title, company, location, url, and full description text.

4. **For each job returned, in order:**
   a. Compare the already-read resume against the job's description. Judge a 0-100% match with
      brief reasoning (which required skills are present/missing) — this is Claude's own semantic
      judgment, not a keyword-overlap script.
   b. **Match < `config.india.json`'s `matchThresholdPercent`:** append a line to `_skipped.jsonl`
      (`jobId, title, company, url, matchPercent, reason: "below threshold", reasoning`); mark
      `seen_jobs.json` so it's not rescored next run; move on — no browser interaction for this job.
   c. **Match >= threshold:** run:
      ```
      python scripts/linkedin.py apply --job-id <id> --job-url <url> --resume-path <config.india.json's resume path> \
        --region in --profile-dir <chrome.profileDir from config> --answers data/answers.json \
        --answer-pipe <a scratch path, e.g. data/.answer_pipe_<id>.json>
      ```
      via `Bash(run_in_background: true)`, and follow it with `Monitor` (each stdout line is one
      JSON event).

5. **Handle apply events as they arrive:**
   - `step_advanced` — informational, no action needed.
   - `question_pending` (`question`, `field_type`, `options`, `required`) — this is a screening
     question the script's pattern list in `data/answers.json` didn't recognize. `field_type` may
     be `text`, `radio`, or `select` (native dropdown); for `radio`/`select`, `options` lists the
     real choices. Ask the user (use `AskUserQuestion` if `options` is a non-empty list; otherwise
     ask directly in chat). Once answered, write the answer to the `--answer-pipe` path as
     `{"answer": "<their answer>"}` via the `Write` tool — the script is polling for that file and
     will resume once it appears. After the job's outcome event arrives, judge whether this Q&A is
     **generic and reusable** (salary expectation, notice period, visa/work permit, relocation,
     gender/EEO, phone, location, years of experience, etc.) versus **company-specific** (e.g. "why
     do you want to join Acme?") or **bucketed/company-tailored option sets** unlikely to recur
     verbatim (e.g. "5+ years / 3-5 years / ..." for a specific tech stack) — the latter two should
     just be answered live, not persisted. If genuinely generic, append a new entry to
     `data/answers.json` (same shape as existing entries: `category`, `match_substrings`,
     `field_type`, `value` or `value_by_region`, and optionally `regions: ["in"]` if the answer is
     India-currency/context-specific and would be wrong if reused for a Europe application) so
     future runs match it automatically without asking again.
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

6. **Summarize.** One short summary: jobs found, skipped below threshold, applied, failed —
   plus the paths to today's two log files.

## Notes

- Never re-introduce a per-job manual-approval gate, and never re-enable other portals
  (Indeed/Naukri/etc.) as part of running this skill — LinkedIn-only auto-apply is a deliberate
  scope decision the user made, not something to silently expand.
- Never search Europe/Japan locations or use the EU resume/config from this skill — that's what
  `apply-jobs-europe` is for. If the user wants both regions run in one sitting, run both skills
  in sequence, not by broadening this skill's config.
- `data/answers.json` is the growing memory of reusable screening-question answers, shared across
  both region skills — treat it as precious. Only add entries there for genuinely reusable answers;
  keep company-specific and bucketed/tailored-option answers out of it, and scope currency/region-
  specific answers (salary figures) with a `regions` key so they can't leak onto the wrong region's
  applications.
- If the resume changes between runs (new role, different seniority, career pivot), search titles
  should shift accordingly next run automatically — no config edit needed. If the user mentions a
  new resume file, update `config.india.json`'s `resume` field to point at it.
- If `search` or `apply` come back empty/wrong repeatedly, LinkedIn's DOM likely changed — say so
  plainly rather than guessing new selectors blind; the user can watch the Chrome window (it runs
  visibly, not headless) to help diagnose.
