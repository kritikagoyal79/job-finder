#!/usr/bin/env python3
# LinkedIn Easy Apply automation: search / apply / record-outcome. See .claude/skills/apply-jobs/SKILL.md for orchestration.
import argparse
import json
import sys
import time
import urllib.parse
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeoutError

MAX_EASY_APPLY_STEPS = 8
ANSWER_WAIT_TIMEOUT_SECONDS = 15 * 60


def log_event(event: dict):
    print(json.dumps(event), flush=True)


def load_json(path, default):
    p = Path(path)
    if not p.exists():
        return default
    return json.loads(p.read_text(encoding="utf-8"))


def save_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2), encoding="utf-8")


def launch_context(playwright, profile_dir):
    return playwright.chromium.launch_persistent_context(
        user_data_dir=profile_dir,
        channel="chrome",
        headless=False,
        args=["--start-maximized"],
    )


# ---------- search ----------

def fetch_description(context, job_url):
    page = context.new_page()
    try:
        page.goto(job_url, wait_until="domcontentloaded")
        page.wait_for_selector("h2", timeout=10000)
        heading = page.query_selector("xpath=//h2[contains(text(),'About the job')]")
        if heading:
            container = heading.query_selector("xpath=../../..")
            if container:
                return container.inner_text().strip()
        return ""
    except PWTimeoutError:
        return ""
    finally:
        page.close()


def cmd_search(args):
    config = load_json(args.config, None)
    if config is None:
        print(json.dumps({"error": f"config not found: {args.config}"}), file=sys.stderr)
        sys.exit(1)
    seen = load_json(args.seen, {})

    titles = config["search"]["titles"]
    locations = config["search"]["locations"]
    max_per_location = config["search"].get("maxResultsPerLocation", 25)
    profile_dir = config["chrome"]["profileDir"]

    results = []
    with sync_playwright() as p:
        context = launch_context(p, profile_dir)
        page = context.pages[0] if context.pages else context.new_page()
        try:
            for location in locations:
                found_for_location = 0
                for title in titles:
                    if found_for_location >= max_per_location:
                        break
                    url = (
                        "https://www.linkedin.com/jobs/search/?keywords="
                        f"{urllib.parse.quote(title)}&location={urllib.parse.quote(location)}"
                    )
                    page.goto(url, wait_until="domcontentloaded")
                    try:
                        page.wait_for_selector(
                            "li[data-occludable-job-id], div.jobs-search-no-results-banner",
                            timeout=15000,
                        )
                    except PWTimeoutError:
                        continue
                    time.sleep(2)  # virtualized list settling

                    for card in page.query_selector_all("li[data-occludable-job-id]"):
                        if found_for_location >= max_per_location:
                            break
                        external_id = card.get_attribute("data-occludable-job-id")
                        if not external_id or external_id in seen:
                            continue
                        card.scroll_into_view_if_needed()
                        time.sleep(0.3)
                        title_el = card.query_selector("a.job-card-list__title--link strong")
                        company_el = card.query_selector("div.artdeco-entity-lockup__subtitle")
                        location_el = card.query_selector("div.artdeco-entity-lockup__caption")
                        job_url = f"https://www.linkedin.com/jobs/view/{external_id}/"

                        results.append({
                            "id": external_id,
                            "title": title_el.inner_text().strip() if title_el else title,
                            "company": company_el.inner_text().strip() if company_el else "",
                            "location": location_el.inner_text().strip() if location_el else location,
                            "url": job_url,
                            "description": fetch_description(context, job_url),
                        })
                        seen[external_id] = {"status": "seen", "lastSeenRun": args.run_timestamp}
                        found_for_location += 1
        finally:
            context.close()

    save_json(args.seen, seen)
    print(json.dumps(results))


# ---------- apply ----------

def match_answer(question_text, answers, field_types):
    q = question_text.lower()
    for entry in answers:
        if entry.get("field_type") not in field_types:
            continue
        for substr in entry.get("match_substrings", []):
            if substr.lower() in q:
                return entry
    return None


def resolve_value(entry, region):
    if "value_by_region" in entry:
        return entry["value_by_region"].get(region) or next(iter(entry["value_by_region"].values()))
    return entry.get("value")


def ask_and_wait(question_text, field_type, options, answer_pipe):
    log_event({
        "event": "question_pending",
        "question": question_text,
        "field_type": field_type,
        "options": options,
        "required": True,
    })
    answer_path = Path(answer_pipe)
    deadline = time.time() + ANSWER_WAIT_TIMEOUT_SECONDS
    while time.time() < deadline:
        if answer_path.exists():
            try:
                data = json.loads(answer_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                time.sleep(1)
                continue
            answer_path.unlink()
            return data["answer"]
        time.sleep(1)
    raise TimeoutError(f"timed out waiting for an answer to: {question_text}")


def fill_current_step(page, answers, args):
    file_input = page.query_selector("input[type='file']")
    if file_input:
        try:
            file_input.set_input_files(args.resume_path)
            page.wait_for_timeout(500)
        except Exception:
            pass

    for label in page.query_selector_all("label[for]"):
        input_id = label.get_attribute("for")
        if not input_id:
            continue
        field = page.query_selector(f"#{input_id}")
        if not field or field.get_attribute("type") == "file":
            continue
        tag = field.evaluate("el => el.tagName.toLowerCase()")
        if tag not in ("input", "textarea"):
            continue
        if (field.input_value() if tag == "input" else field.inner_text()):
            continue  # already filled
        question_text = label.inner_text().strip()
        entry = match_answer(question_text, answers, ("text",))
        if entry:
            field.fill(resolve_value(entry, args.region))
            continue
        required = field.get_attribute("required") is not None or field.get_attribute("aria-required") == "true"
        if not required:
            continue
        field.fill(ask_and_wait(question_text, "text", None, args.answer_pipe))

    for field in page.query_selector_all("input[aria-label], textarea[aria-label]"):
        if field.get_attribute("id"):
            continue  # already handled via label[for] above
        question_text = (field.get_attribute("aria-label") or "").strip()
        if field.input_value():
            continue
        entry = match_answer(question_text, answers, ("text",))
        if entry:
            field.fill(resolve_value(entry, args.region))
            continue
        required = field.get_attribute("required") is not None or field.get_attribute("aria-required") == "true"
        if not required:
            continue
        field.fill(ask_and_wait(question_text, "text", None, args.answer_pipe))

    for fieldset in page.query_selector_all("fieldset"):
        inputs = fieldset.query_selector_all("input[type='radio'], input[type='checkbox']")
        if not inputs or any(i.is_checked() for i in inputs):
            continue
        legend = fieldset.query_selector("legend")
        question_text = (legend.inner_text() if legend else fieldset.inner_text()).strip()

        options = []
        for i in inputs:
            input_id = i.get_attribute("id")
            label_text = None
            if input_id:
                lbl = fieldset.query_selector(f"label[for='{input_id}']")
                if lbl:
                    label_text = lbl.inner_text().strip()
            options.append(label_text or i.get_attribute("value") or "")

        entry = match_answer(question_text, answers, ("yes_no", "radio"))
        if entry:
            value = resolve_value(entry, args.region)
            answer_value = ("Yes" if str(value).lower() in ("yes", "true") else "No") \
                if entry.get("field_type") == "yes_no" else value
        else:
            answer_value = ask_and_wait(question_text, "radio", options, args.answer_pipe)

        target = None
        for i, opt_label in zip(inputs, options):
            if opt_label.strip().lower() == str(answer_value).strip().lower():
                target = i
                break
        if target is None and len(inputs) == 2:
            target = inputs[0] if str(answer_value).strip().lower() in ("yes", "true") else inputs[1]
        (target or inputs[0]).evaluate("el => el.click()")


def cmd_apply(args):
    answers = load_json(args.answers, [])

    with sync_playwright() as p:
        context = launch_context(p, args.profile_dir)
        page = context.pages[0] if context.pages else context.new_page()
        try:
            page.goto(args.job_url, wait_until="domcontentloaded")

            if page.query_selector("xpath=//*[contains(text(),'No longer accepting applications')]"):
                log_event({"event": "error", "reason": "listing closed"})
                return

            easy_apply_btn = page.query_selector("xpath=//button[contains(@aria-label,'Easy Apply')]")
            if not easy_apply_btn:
                log_event({"event": "error", "reason": "no Easy Apply button found"})
                return
            easy_apply_btn.click()
            page.wait_for_timeout(1000)

            for step in range(1, MAX_EASY_APPLY_STEPS + 1):
                fill_current_step(page, answers, args)

                submit_btn = page.query_selector(
                    "xpath=//button[contains(@aria-label,'Submit application') or "
                    "normalize-space(.)='Submit application' or normalize-space(.)='Submit']"
                )
                if submit_btn:
                    submit_btn.click()
                    page.wait_for_timeout(1500)
                    log_event({"event": "applied", "jobId": args.job_id})
                    return

                next_btn = page.query_selector(
                    "xpath=//button[contains(@aria-label,'Continue to next step') or "
                    "contains(@aria-label,'Review your application') or "
                    "normalize-space(.)='Next' or normalize-space(.)='Review' or "
                    "normalize-space(.)='Continue']"
                )
                if not next_btn:
                    log_event({"event": "error", "reason": "no Next/Submit button found on step"})
                    return

                next_btn.click()
                try:
                    page.wait_for_selector(
                        "xpath=//button[contains(@aria-label,'Continue to next step')]",
                        state="detached", timeout=5000,
                    )
                except PWTimeoutError:
                    pass
                page.wait_for_timeout(800)
                log_event({"event": "step_advanced", "step": step + 1})

            log_event({"event": "error", "reason": f"exceeded {MAX_EASY_APPLY_STEPS} steps"})
        except TimeoutError as e:
            log_event({"event": "error", "reason": str(e)})
        except Exception as e:
            log_event({"event": "error", "reason": f"{type(e).__name__}: {e}"})
        finally:
            context.close()


# ---------- record-outcome ----------

def cmd_record_outcome(args):
    seen = load_json(args.seen, {})
    seen[args.job_id] = {"status": args.status, "lastSeenRun": args.run_timestamp}
    save_json(args.seen, seen)
    print(json.dumps({"ok": True}))


def main():
    parser = argparse.ArgumentParser(description="LinkedIn Easy Apply automation")
    sub = parser.add_subparsers(dest="command", required=True)

    p_search = sub.add_parser("search")
    p_search.add_argument("--config", required=True)
    p_search.add_argument("--seen", required=True)
    p_search.add_argument("--run-timestamp", dest="run_timestamp", required=True)
    p_search.set_defaults(func=cmd_search)

    p_apply = sub.add_parser("apply")
    p_apply.add_argument("--job-id", required=True)
    p_apply.add_argument("--job-url", required=True)
    p_apply.add_argument("--resume-path", required=True)
    p_apply.add_argument("--region", required=True, choices=["in", "eu"])
    p_apply.add_argument("--profile-dir", required=True)
    p_apply.add_argument("--answers", required=True)
    p_apply.add_argument("--answer-pipe", required=True)
    p_apply.set_defaults(func=cmd_apply)

    p_record = sub.add_parser("record-outcome")
    p_record.add_argument("--job-id", required=True)
    p_record.add_argument("--status", required=True)
    p_record.add_argument("--seen", required=True)
    p_record.add_argument("--run-timestamp", dest="run_timestamp", required=True)
    p_record.set_defaults(func=cmd_record_outcome)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
