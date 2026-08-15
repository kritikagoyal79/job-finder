#!/usr/bin/env python3
# LinkedIn Easy Apply automation: search / apply / record-outcome. See .claude/skills/apply-jobs-india or apply-jobs-europe SKILL.md for orchestration.
import argparse
import json
import sys
import time
import urllib.parse
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeoutError

MAX_EASY_APPLY_STEPS = 15  # some real applications (e.g. Seven Senders) legitimately run 9+ steps
ANSWER_WAIT_TIMEOUT_SECONDS = 15 * 60


def log_event(event: dict):
    print(json.dumps(event), flush=True)


def dump_debug_state(page, args):
    # Captured only on unrecognized-step failures so a stuck Easy Apply form can be diagnosed
    # after the fact instead of re-reproducing it live -- screenshot for the visual layout, HTML
    # for the exact selectors/aria-labels LinkedIn actually shipped on that step.
    debug_dir = Path(args.answers).parent / "debug"
    debug_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    base = debug_dir / f"{args.job_id}_{stamp}"
    try:
        page.screenshot(path=str(base.with_suffix(".png")), full_page=True)
    except Exception:
        pass
    try:
        base.with_suffix(".html").write_text(page.content(), encoding="utf-8")
    except Exception:
        pass
    return str(base)


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
        # Waiting on a bare "h2" matches an earlier-rendering nav heading (e.g. "0 notifications")
        # before the job description section has hydrated, so target the specific heading text.
        heading_selector = "xpath=//h2[contains(text(),'About the job')]"
        try:
            page.wait_for_selector(heading_selector, timeout=10000)
        except PWTimeoutError:
            return ""
        heading = page.query_selector(heading_selector)
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

    # --titles (derived by Claude from whatever resume is currently in resume/) overrides the
    # config's static list, so search terms track the profile in use instead of staying fixed to
    # whoever originally set the config up.
    titles = [t.strip() for t in args.titles.split(",")] if args.titles else config["search"]["titles"]
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

def match_answer(question_text, answers, field_types, region):
    q = question_text.lower()
    for entry in answers:
        if entry.get("field_type") not in field_types:
            continue
        # Currency/region-specific answers (e.g. salary figures) must not leak across regions --
        # an INR-lakhs figure silently submitted on a EUR-denominated field would be wrong, not
        # just imprecise. Entries without a "regions" key apply everywhere (e.g. notice period).
        if "regions" in entry and region not in entry["regions"]:
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
    else:
        # Some Easy Apply resume steps render only an "Upload resume" button with no
        # <input type="file"> anywhere in the DOM until it's clicked -- LinkedIn opens a
        # native OS file-chooser dialog on click instead, which set_input_files can't
        # target. Left unhandled, the step silently stays on "resume required" and the
        # apply loop burns through MAX_EASY_APPLY_STEPS re-clicking Next against it.
        upload_btn = None
        for b in page.query_selector_all("button"):
            try:
                text = b.inner_text().strip().lower()
            except Exception:
                continue
            if "upload resume" in text:
                upload_btn = b
                break
        if upload_btn:
            try:
                with page.expect_file_chooser(timeout=5000) as fc_info:
                    upload_btn.click()
                fc_info.value.set_files(args.resume_path)
                page.wait_for_timeout(1000)
            except Exception:
                pass

    labeled_ids = set()
    for label in page.query_selector_all("label[for]"):
        input_id = label.get_attribute("for")
        if not input_id:
            continue
        # Attribute selector, not "#id" -- LinkedIn's React-generated ids (e.g. "«rd»") contain
        # characters that aren't valid in an unescaped CSS id selector.
        field = page.query_selector(f"[id='{input_id}']")
        if not field or field.get_attribute("type") == "file":
            continue
        labeled_ids.add(input_id)
        tag = field.evaluate("el => el.tagName.toLowerCase()")
        if tag not in ("input", "textarea"):
            continue
        if (field.input_value() if tag == "input" else field.inner_text()):
            continue  # already filled
        question_text = label.inner_text().strip()
        entry = match_answer(question_text, answers, ("text",), args.region)
        if entry:
            field.fill(resolve_value(entry, args.region))
            continue
        required = field.get_attribute("required") is not None or field.get_attribute("aria-required") == "true"
        if not required:
            continue
        field.fill(ask_and_wait(question_text, "text", None, args.answer_pipe))

    # Dropdown (<select>) screening questions -- e.g. "years of experience with X" or "which
    # databases have you used" bucketed choices. These were previously invisible to this function
    # entirely (the label[for] loop above only follows through for input/textarea), which is why a
    # form with a required, still-blank dropdown looked like an infinite step-advance loop: Next
    # kept "succeeding" while the real validation error sat on a field type nothing ever touched.
    for select_el in page.query_selector_all("select"):
        if select_el.evaluate("el => el.value"):
            continue  # already has a real (non-placeholder) selection
        select_id = select_el.get_attribute("id")
        question_text = ""
        if select_id:
            lbl = page.query_selector(f"label[for='{select_id}']")
            if lbl:
                question_text = lbl.inner_text().strip()
        if not question_text:
            question_text = (select_el.get_attribute("aria-label") or "").strip()
        options = [
            opt.inner_text().strip()
            for opt in select_el.query_selector_all("option")
            if opt.get_attribute("value")
        ]
        required = select_el.get_attribute("required") is not None
        if not required:
            continue
        entry = match_answer(question_text, answers, ("select",), args.region)
        answer_value = resolve_value(entry, args.region) if entry else ask_and_wait(
            question_text, "select", options, args.answer_pipe
        )
        matched_value = None
        for opt in select_el.query_selector_all("option"):
            if opt.inner_text().strip().lower() == str(answer_value).strip().lower():
                matched_value = opt.get_attribute("value")
                break
        select_el.select_option(matched_value if matched_value else str(answer_value))
        page.wait_for_timeout(500)  # let any reactive re-render (e.g. cascading fields) settle

    for field in page.query_selector_all("input[aria-label], textarea[aria-label]"):
        input_id = field.get_attribute("id")
        if input_id and input_id in labeled_ids:
            continue  # already handled via label[for] above
        question_text = (field.get_attribute("aria-label") or "").strip()
        if field.input_value():
            continue
        entry = match_answer(question_text, answers, ("text",), args.region)
        if entry:
            field.fill(resolve_value(entry, args.region))
            continue
        required = field.get_attribute("required") is not None or field.get_attribute("aria-required") == "true"
        if not required:
            continue
        field.fill(ask_and_wait(question_text, "text", None, args.answer_pipe))

    for fieldset in page.query_selector_all("fieldset"):
        inputs = fieldset.query_selector_all("input[type='radio'], input[type='checkbox']")
        if not inputs:
            continue

        fieldset_text_lower = fieldset.inner_text().lower()
        if ".pdf" in fieldset_text_lower or ".doc" in fieldset_text_lower:
            # LinkedIn's resume-picker also renders as a radiogroup fieldset -- it's not a
            # screening question, so it must never fall into the yes/no answer path below.
            # Select whichever listed resume matches the one we were asked to use; if it isn't
            # listed, leave the existing selection rather than guess (resume content is the same
            # across regions per config -- only the phone number differs).
            target_name = Path(args.resume_path).name.lower()
            for i in inputs:
                input_id = i.get_attribute("id")
                option_text = ""
                if input_id:
                    lbl = fieldset.query_selector(f"label[for='{input_id}']")
                    if lbl:
                        option_text = lbl.inner_text().lower()
                if target_name in option_text and not i.is_checked():
                    i.evaluate("el => el.click()")
                    break
            continue

        if any(i.is_checked() for i in inputs):
            continue

        # Question text isn't always in a <legend> -- LinkedIn also puts it in aria-label or
        # aria-labelledby on the fieldset itself. Falling straight through to fieldset.inner_text()
        # without checking these first returns just the option words (e.g. "Yes\nNo") with no
        # actual question, which is unusable for the user prompt and unmatchable in answers.json.
        legend = fieldset.query_selector("legend")
        question_text = legend.inner_text().strip() if legend else ""
        if not question_text:
            question_text = (fieldset.get_attribute("aria-label") or "").strip()
        if not question_text:
            labelledby = fieldset.get_attribute("aria-labelledby")
            if labelledby:
                parts = [page.query_selector(f"[id='{ref}']") for ref in labelledby.split()]
                question_text = " ".join(p.inner_text().strip() for p in parts if p).strip()
        if not question_text:
            # LinkedIn also renders the question as a plain <p> that's a preceding sibling of the
            # fieldset in their shared parent -- not linked via any ARIA attribute at all.
            question_text = fieldset.evaluate("el => el.previousElementSibling?.innerText?.trim() || ''")
        if not question_text:
            question_text = fieldset.inner_text().strip()
        question_text = question_text.rstrip("*").strip()

        options = []
        for i in inputs:
            input_id = i.get_attribute("id")
            label_text = None
            if input_id:
                lbl = fieldset.query_selector(f"label[for='{input_id}']")
                if lbl:
                    label_text = lbl.inner_text().strip()
            if not label_text:
                label_text = (i.get_attribute("aria-label") or "").strip() or None
            if not label_text:
                # The <label for> element is often empty -- the visible "Yes"/"No" text instead
                # sits in a sibling <p> inside the same role="radio" wrapper div.
                wrapper_text = i.evaluate("el => el.closest('[role=\"radio\"], [role=\"checkbox\"]')?.innerText?.trim() || ''")
                label_text = wrapper_text or None
            options.append(label_text or i.get_attribute("value") or "")

        entry = match_answer(question_text, answers, ("yes_no", "radio"), args.region)
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
            # domcontentloaded fires before LinkedIn's SPA hydrates the apply-button area, so wait
            # for one of the possible outcomes to actually render before deciding (same class of
            # race as fetch_description's earlier bug).
            indicator_selector = (
                "xpath=//button[contains(@aria-label,'Easy Apply')] | "
                "//*[self::a or self::button][contains(@aria-label,'Apply on company website')] | "
                "//*[contains(text(),'No longer accepting applications')] | "
                "//button[contains(.,\"I'm interested\") or contains(.,\"I’m interested\")]"
            )
            try:
                page.wait_for_selector(indicator_selector, timeout=15000)
            except PWTimeoutError:
                log_event({"event": "error", "reason": "page did not settle (no apply indicator rendered)"})
                return

            if page.query_selector("xpath=//*[contains(text(),'No longer accepting applications')]"):
                log_event({"event": "error", "reason": "listing closed"})
                return

            if page.query_selector(
                "xpath=//*[self::a or self::button][contains(@aria-label,'Apply on company website')]"
            ):
                log_event({"event": "error", "reason": "external apply only, not Easy Apply"})
                return

            if page.query_selector(
                "xpath=//button[contains(.,\"I'm interested\") or contains(.,\"I’m interested\")]"
            ) and not page.query_selector(
                "xpath=//button[contains(@aria-label,'Easy Apply')]"
            ):
                log_event({"event": "error", "reason": "interested-only listing, no direct apply mechanism"})
                return

            if page.locator("xpath=//button[contains(@aria-label,'Easy Apply')]").count() == 0:
                log_event({"event": "error", "reason": "no Easy Apply button found"})
                return
            # Locator.click() re-resolves the element right before acting, unlike a query_selector
            # handle -- LinkedIn sometimes re-renders this button shortly after paint, which made a
            # held ElementHandle throw "not attached to the DOM" on click.
            page.locator("xpath=//button[contains(@aria-label,'Easy Apply')]").first.click(timeout=15000)
            page.wait_for_timeout(1000)

            for step in range(1, MAX_EASY_APPLY_STEPS + 1):
                fill_current_step(page, answers, args)

                # LinkedIn has shipped both long aria-labels ("Submit application", "Continue to
                # next step") and short ones ("Submit", "Next") with empty visible button text
                # across different rollouts -- match both forms rather than assume one.
                submit_selector = (
                    "xpath=//button[contains(@aria-label,'Submit application') or "
                    "@aria-label='Submit' or "
                    "normalize-space(.)='Submit application' or normalize-space(.)='Submit']"
                )
                if page.locator(submit_selector).count() > 0:
                    page.locator(submit_selector).first.click(timeout=10000)
                    page.wait_for_timeout(1500)
                    log_event({"event": "applied", "jobId": args.job_id})
                    return

                next_selector = (
                    "xpath=//button[contains(@aria-label,'Continue to next step') or "
                    "contains(@aria-label,'Review your application') or "
                    "@aria-label='Next' or @aria-label='Review' or @aria-label='Continue' or "
                    "normalize-space(.)='Next' or normalize-space(.)='Review' or "
                    "normalize-space(.)='Continue']"
                )
                if page.locator(next_selector).count() == 0:
                    debug_path = dump_debug_state(page, args)
                    log_event({
                        "event": "error",
                        "reason": "no Next/Submit button found on step",
                        "debug": debug_path,
                    })
                    return

                page.locator(next_selector).first.click(timeout=10000)
                try:
                    page.wait_for_selector(
                        "xpath=//button[contains(@aria-label,'Continue to next step')]",
                        state="detached", timeout=5000,
                    )
                except PWTimeoutError:
                    pass
                page.wait_for_timeout(800)

                # A timed-out detach above doesn't necessarily mean nothing changed -- confirm by
                # checking for LinkedIn's own validation error text, rather than silently assuming
                # the click advanced the wizard (a prior version of this code did that and looped
                # on the same stuck step until it hit the step cap).
                error_el = (
                    page.query_selector("div.artdeco-inline-feedback--error, span[class*='error-message']")
                    or page.query_selector("xpath=//*[normalize-space(text())='Invalid input']")
                )
                if error_el:
                    log_event({
                        "event": "error",
                        "reason": f"step did not advance, validation error present: {error_el.inner_text().strip()[:200]}",
                    })
                    return

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
    p_search.add_argument(
        "--titles",
        default=None,
        help="Comma-separated job titles to search, overriding config's search.titles "
             "(intended to be derived from the current resume rather than typed by hand)",
    )
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
