#!/usr/bin/env python3
"""Generate ``SupportedLocales.kt`` from ``tools/i18n/locales.json`` plus computed coverage.

The catalogue itself is the single source of truth (see ``docs/internationalization.md`` 4c).
Coverage is **never** stored in ``locales.json`` — it is computed here from the actual Android
resource files, mirroring what ``validate_strings.py`` enforces in CI, so the picker's
"completeness %" column and the CI gate can never disagree (they are the same number from the same
run).

Run after editing ``locales.json`` or the res tree:

    python3 tools/i18n/gen_supported_locales.py

The emitted file is checked in; a future Phase 4 build task can re-run it automatically.
"""
from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCALES_JSON = ROOT / "tools" / "i18n" / "locales.json"
# Translatable resources live in :core (see validate_strings.py), and so does the generated file.
RES = ROOT / "core" / "src" / "main" / "res"
# The generated table lives in :core (every app built on core reads the same locale list); the
# string resources it is generated FROM are still the TV app's until they move too.
OUT = ROOT / "core" / "src" / "main" / "java" / "tv" / "own" / "owntv" / "core" / "i18n" / "SupportedLocales.kt"
CANONICAL_OUT = OUT
COMMUNITY_CONFIG = ROOT / "tools" / "i18n" / "community.json"
README = ROOT / "README.md"
GUIDE = ROOT / "tools" / "i18n" / "README.md"
README_START = "<!-- i18n-contribution:start -->"
README_END = "<!-- i18n-contribution:end -->"
GUIDE_START = "<!-- canonical-weblate:start -->"
GUIDE_END = "<!-- canonical-weblate:end -->"

PACKAGE = "tv.own.owntv.core.i18n"


def _community_config() -> dict:
    return json.loads(COMMUNITY_CONFIG.read_text(encoding="utf-8"))


def _readme_contribution(config: dict) -> str:
    url = config["projectUrl"]
    request_url = config["languageRequestUrl"]
    warning = (
        "> **Captain-configured rehearsal endpoint:** this URL is intentionally recorded without an "
        "independent production-route verification.\n\n"
        if config.get("localTestOnly") else ""
    )
    return (
        f"{README_START}\n## Help translate OwnTV\n\n{warning}"
        "If your language is already available, contribute interface translations across OwnTV's six "
        f"Android resource components on [Hosted Weblate]({url}). If it is not listed, "
        f"[open a language request ticket]({request_url}) first. A maintainer will review the request, "
        "register the locale, and prepare its base translation files on Hosted Weblate. Once the "
        "language appears on Hosted Weblate, you can start translating it there. See the "
        "[language contributor guide](tools/i18n/README.md) for identifiers, validation, and promotion "
        f"policy.\n{README_END}"
    )


def _guide_contribution(config: dict) -> str:
    url = config["projectUrl"]
    request_url = config["languageRequestUrl"]
    return (
        f"{GUIDE_START}\n"
        f"**Canonical project overview:** <{url}>\n\n"
        f"**New-language request form:** <{request_url}>\n\n"
        "The app, README, and this guide use these values from `community.json`; update that single "
        "source when either route changes.\n"
        f"{GUIDE_END}"
    )


def _replace_marked_block(path: Path, start_marker: str, end_marker: str, expected: str, check: bool) -> bool:
    text = path.read_text(encoding="utf-8")
    if start_marker in text and end_marker in text:
        start = text.index(start_marker)
        end = text.index(end_marker, start) + len(end_marker)
        updated = text[:start] + expected + text[end:]
    else:
        updated = text.rstrip() + "\n\n" + expected + "\n"
    if check:
        return updated == text
    path.write_text(updated, encoding="utf-8")
    return True


def _update_readme(config: dict, check: bool) -> bool:
    return _replace_marked_block(README, README_START, README_END, _readme_contribution(config), check)


def _update_guide(config: dict, check: bool) -> bool:
    return _replace_marked_block(GUIDE, GUIDE_START, GUIDE_END, _guide_contribution(config), check)



# A locale with no translated resources has real 0% coverage. This keeps the generated picker badge
# identical to validate_strings.py's informational CI report.
EMPTY_COVERAGE = 0


def _string_keys(directory: Path) -> set[str]:
    """Translatable entry keys in a ``values[-x]`` directory.

    Skips ``donottranslate.xml`` (brand + protocol constants, all ``translatable="false"``) and any
    string flagged ``translatable="false"``. ``<plurals>`` keys are suffixed with ``#`` and
    ``<string-array>`` keys with ``[]`` so entries of different kinds sharing a base name never
    collapse the coverage count. This must match ``validate_strings.py``'s suffix scheme exactly so
    the picker's coverage column and the CI gate agree.
    """
    if not directory.is_dir():
        return set()
    keys: set[str] = set()
    for f in sorted(directory.glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            sys.exit(f"error: {f}: {e}")
        for el in root:
            name = el.get("name")
            if not name:
                continue
            if el.tag == "string":
                if el.get("translatable") == "false":
                    continue
                keys.add(name)
            elif el.tag == "plurals":
                if el.get("translatable") != "false":
                    keys.add(name + "#")
            elif el.tag == "string-array":
                if el.get("translatable") != "false":
                    keys.add(name + "[]")
    return keys


def _kt_string(s: str) -> str:
    out = ['"']
    for ch in s:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif ch == "\n":
            out.append("\\n")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def _generate() -> tuple[str, int, int]:
    """Return (generated_text, num_entries, num_source_keys)."""
    data = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    config = _community_config()
    threshold = config["translationReadinessThresholdPercent"]
    project_url = config["projectUrl"]
    language_request_url = config["languageRequestUrl"]
    source_keys = _string_keys(RES / "values")

    entries: list[dict] = []
    for e in data:
        resdir = e["resourceDirectory"]
        if resdir == "values":
            coverage = 100  # source language: every translatable source key is present by definition
        else:
            locale_keys = _string_keys(RES / resdir)
            # No directory and an empty directory both mean the same user-visible thing: 0% of
            # source keys currently have localized values.
            coverage = EMPTY_COVERAGE if not source_keys or not locale_keys else round(100 * len(locale_keys & source_keys) / len(source_keys))
        entries.append({**e, "coverage": coverage})

    L: list[str] = []
    L.append("// DO NOT EDIT — generated by tools/i18n/gen_supported_locales.py from")
    L.append("// tools/i18n/locales.json together with the current Android resource tree.")
    L.append("// Coverage is computed at generation time; it is never stored in locales.json.")
    L.append("// Re-run the generator after editing locales.json or any values*/strings*.xml.")
    L.append("")
    L.append("@file:Suppress(\"unused\")")
    L.append(f"package {PACKAGE}")
    L.append("")
    L.append("/**")
    L.append(" * One supported locale, as consumed by the in-app language picker (Phase 2) and the locale")
    L.append(" * runtime. Mirrors ``tools/i18n/locales.json`` exactly; ``coverage`` is computed from the")
    L.append(" * actual resource files by the generator, never stored.")
    L.append(" */")
    L.append("@Suppress(\"unused\")")
    L.append("data class SupportedLocale(")
    L.append("    val id: String,")
    L.append("    /** BCP-47 runtime tag; the picker's System-default row writes the empty tag. */")
    L.append("    val languageTag: String,")
    L.append("    /** Android resource directory qualifier, e.g. `pt` or `zh-rCN`. */")
    L.append("    val resourceQualifier: String,")
    L.append("    /** Weblate language code, e.g. `pt_BR`. */")
    L.append("    val weblateCode: String,")
    L.append("    val englishName: String,")
    L.append("    val endonym: String,")
    L.append("    /** ISO 15924 script code, e.g. `Latn`, `Arab`, `Cyrl`, `Hans`. */")
    L.append("    val script: String,")
    L.append("    val rtl: Boolean,")
    L.append("    val tier: Int,")
    L.append("    val packaged: Boolean,")
    L.append("    val pickerVisible: Boolean,")
    L.append("    /** Computed coverage of translatable source keys, 0..100. */")
    L.append("    val coverage: Int,")
    L.append(")")
    L.append("")
    L.append("@Suppress(\"unused\")")
    L.append("object SupportedLocales {")
    L.append("")
    L.append("    /** Community translations are promoted to shipping only at this coverage boundary. */")
    L.append(f"    const val TRANSLATION_READINESS_THRESHOLD_PERCENT: Int = {threshold}")
    L.append("")
    L.append("    /** Canonical Hosted Weblate project overview used by the app and generated documentation. */")
    L.append("    const val CONTRIBUTION_PROJECT_URL: String = " + _kt_string(project_url))
    L.append("")
    L.append("    /** Canonical issue form for requesting a locale before its base files exist. */")
    L.append("    const val LANGUAGE_REQUEST_URL: String = " + _kt_string(language_request_url))
    L.append("")
    L.append("    /** Tag meaning \"follow the current device locale list\" (see ``LocaleStore``). */")
    L.append('    const val SYSTEM_DEFAULT_TAG: String = ""')
    L.append("")
    L.append("    val all: List<SupportedLocale> = listOf(")
    for e in entries:
        L.append("        SupportedLocale(")
        L.append("            id = " + _kt_string(e["id"]) + ",")
        L.append("            languageTag = " + _kt_string(e["languageTag"]) + ",")
        L.append("            resourceQualifier = " + _kt_string(e["resourceQualifier"]) + ",")
        L.append("            weblateCode = " + _kt_string(e["weblateCode"]) + ",")
        L.append("            englishName = " + _kt_string(e["englishName"]) + ",")
        L.append("            endonym = " + _kt_string(e["endonym"]) + ",")
        L.append("            script = " + _kt_string(e["script"]) + ",")
        L.append("            rtl = " + ("true" if e["rtl"] else "false") + ",")
        L.append("            tier = " + str(e["tier"]) + ",")
        L.append("            packaged = " + ("true" if e["packaged"] else "false") + ",")
        L.append("            pickerVisible = " + ("true" if e["pickerVisible"] else "false") + ",")
        L.append("            coverage = " + str(e["coverage"]) + ",")
        L.append("        ),")
    L.append("    )")
    L.append("")
    L.append("    /** Rows the in-app picker may show after explicit catalogue promotion and readiness. */")
    L.append("    val pickerRows: List<SupportedLocale> get() = all.filter {")
    L.append("        (it.id == \"en-US\" || it.coverage >= TRANSLATION_READINESS_THRESHOLD_PERCENT) &&")
    L.append("            it.packaged && it.pickerVisible")
    L.append("    }")
    L.append("")
    L.append("    /** A community locale is ready for manual packaging/picker promotion at the readiness threshold or above. */")
    L.append("    fun isTranslationReady(coverage: Int): Boolean =")
    L.append("        coverage >= TRANSLATION_READINESS_THRESHOLD_PERCENT")
    L.append("")
    L.append("    /** Visible community coverage badge; complete/source/system rows have no badge. */")
    L.append("    fun coverageBadgePercent(locale: SupportedLocale): Int? =")
    L.append("        locale.coverage.takeIf { locale.tier == 1 && locale.id != \"en-US\" && locale.pickerVisible && it < 100 }")
    L.append("")
    L.append("    /** Canonicalize a supported runtime tag; empty means follow the system locale. */")
    L.append("    fun canonicalTag(raw: String?): String? {")
    L.append("        val trimmed = raw?.trim() ?: return null")
    L.append("        if (trimmed.isEmpty()) return SYSTEM_DEFAULT_TAG")
    L.append("        return all.firstOrNull { it.languageTag.equals(trimmed, ignoreCase = true) }?.languageTag")
    L.append("    }")
    L.append("")
    L.append("    /** Look up the ISO 15924 script for a BCP-47 tag, or null if it is not a catalogue entry. */")
    L.append("    fun scriptForTag(tag: String): String? =")
    L.append("        all.firstOrNull { it.languageTag == tag }?.script")
    L.append("")
    L.append("    /** True for an RTL script (Arabic, ...). False for the system-default empty tag. */")
    L.append("    fun isRtl(tag: String): Boolean = all.firstOrNull { it.languageTag == tag }?.rtl == true")
    L.append("}")
    L.append("")
    return "\n".join(L), len(entries), len(source_keys)


def cmd_generate() -> int:
    text, n_entries, n_keys = _generate()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    if OUT == CANONICAL_OUT:
        config = _community_config()
        _update_readme(config, check=False)
        _update_guide(config, check=False)
        print("updated README.md and tools/i18n/README.md")
    print(f"generated {OUT.relative_to(ROOT)} ({n_entries} locales, {n_keys} source keys)")
    return 0


def _check_community_artifacts() -> int:
    config = _community_config()
    stale = []
    if not _update_readme(config, check=True):
        stale.append("README contribution section")
    if not _update_guide(config, check=True):
        stale.append("i18n contributor guide canonical URL")
    if stale:
        print("community contribution artifacts are STALE: " + ", ".join(stale))
        print("  python3 tools/i18n/gen_supported_locales.py")
        return 1
    return 0


def cmd_check() -> int:
    """Fail if the checked-in SupportedLocales.kt is stale relative to locales.json + the res tree.

    CI runs this so a PR that edits the catalogue or adds a translation file cannot forget to
    regenerate — the picker's coverage column and the CI gate both read the generated file, so a
    stale check-in makes them disagree with the truth.
    """
    if not OUT.is_file():
        print(f"error: {OUT.relative_to(ROOT)} missing; run gen_supported_locales.py")
        return 1
    text, n_entries, n_keys = _generate()
    current = OUT.read_text(encoding="utf-8")
    if current == text:
        if OUT == CANONICAL_OUT and _check_community_artifacts():
            return 1
        print(f"SupportedLocales.kt and community artifacts fresh ({n_entries} locales, {n_keys} source keys)")
        return 0
    print("SupportedLocales.kt is STALE — regenerate with:")
    print("  python3 tools/i18n/gen_supported_locales.py")
    return 1


def main() -> int:
    import argparse
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=False)
    sub.add_parser("generate", help="write SupportedLocales.kt (default)")
    sub.add_parser("check", help="fail if SupportedLocales.kt is stale")
    args = ap.parse_args()
    if args.cmd == "check":
        return cmd_check()
    return cmd_generate()


if __name__ == "__main__":
    raise SystemExit(main())
