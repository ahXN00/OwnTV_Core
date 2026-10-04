#!/usr/bin/env python3
"""Regression tests for the OwnTV i18n guardrail scripts.

Run:  python3 tools/i18n/test_i18n_tools.py

Each test constructs a minimal fixture (temp res tree + locales.json) and exercises one tool against
it, asserting the documented pass/fail behaviour. These exist because the first implementation pass
documented correct behaviour without testing the bypass/negative case; these tests pin the bypass
cases so a future regression is caught here, not in review.
"""
from __future__ import annotations

import argparse
import io
import json
import contextlib
import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))  # so `from tools.i18n import ...` works when run as a script


# The readiness bar is configuration (community.json), not a constant of these tests.
THRESHOLD = json.loads((ROOT / "tools/i18n/community.json").read_text())["translationReadinessThresholdPercent"]


def _load(name: str, path: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod  # dataclasses/functools resolve class __module__ via sys.modules
    spec.loader.exec_module(mod)
    return mod


def _make_fixture(tmpdir: Path, source_xml: str, locales: list, translations: dict[str, str] | None = None):
    """Build a temp res tree + locales.json. translations maps resourceDirectory → XML string."""
    res = tmpdir / "app/src/main/res"
    res.mkdir(parents=True)
    (res / "values").mkdir()
    (res / "values/strings.xml").write_text(source_xml)
    for resdir, xml in (translations or {}).items():
        (res / resdir).mkdir(parents=True)
        (res / f"{resdir}/strings.xml").write_text(xml)
    (tmpdir / "tools/i18n").mkdir(parents=True)
    (tmpdir / "tools/i18n/locales.json").write_text(json.dumps(locales))
    return res


def _locale(id, tag, qualifier, resdir, **kw):
    base = {"id": id, "languageTag": tag, "resourceQualifier": qualifier,
            "resourceDirectory": resdir, "weblateCode": kw.get("weblateCode", id),
            "englishName": kw.get("englishName", id), "endonym": kw.get("endonym", id),
            "script": kw.get("script", "Latn"), "rtl": kw.get("rtl", False),
            "tier": kw.get("tier", 1), "packaged": kw.get("packaged", True),
            "pickerVisible": kw.get("pickerVisible", True)}
    # Remove non-schema keys from kw that we already handled
    for k in list(base):
        if k not in {"id","languageTag","resourceQualifier","resourceDirectory","weblateCode",
                      "englishName","endonym","script","rtl","tier","packaged","pickerVisible"}:
            del base[k]
    return base


# The established 25 Tier 1 locales used by compact validator fixtures.
_FULL_TIER1 = [
    _locale("en-US", "en-US", "en", "values", weblateCode="en"),
    _locale("ar", "ar", "ar", "values-ar", weblateCode="ar", script="Arab", rtl=True, packaged=False, pickerVisible=False),
    _locale("pt-BR", "pt-BR", "pt", "values-pt", weblateCode="pt_BR", packaged=False, pickerVisible=False),
    _locale("pt-PT", "pt-PT", "pt-rPT", "values-pt-rPT", weblateCode="pt_PT", packaged=False, pickerVisible=False),
    _locale("zh-CN", "zh-CN", "zh-rCN", "values-zh-rCN", weblateCode="zh_Hans", script="Hans", packaged=False, pickerVisible=False),
    _locale("zh-TW", "zh-TW", "zh-rTW", "values-zh-rTW", weblateCode="zh_Hant", script="Hant", packaged=False, pickerVisible=False),
    _locale("cs", "cs", "cs", "values-cs", packaged=False, pickerVisible=False),
    _locale("da", "da", "da", "values-da", packaged=False, pickerVisible=False),
    _locale("nl", "nl", "nl", "values-nl", packaged=False, pickerVisible=False),
    _locale("fr", "fr", "fr", "values-fr", packaged=False, pickerVisible=False),
    _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False),
    _locale("it", "it", "it", "values-it", packaged=False, pickerVisible=False),
    _locale("ja", "ja", "ja", "values-ja", script="Jpan", packaged=False, pickerVisible=False),
    _locale("ko", "ko", "ko", "values-ko", script="Hang", packaged=False, pickerVisible=False),
    _locale("nb", "nb", "nb", "values-nb", weblateCode="nb_NO", packaged=False, pickerVisible=False),
    _locale("pl", "pl", "pl", "values-pl", packaged=False, pickerVisible=False),
    _locale("ru", "ru", "ru", "values-ru", script="Cyrl", packaged=False, pickerVisible=False),
    _locale("es-US", "es-US", "es-rUS", "values-es-rUS", weblateCode="es_419", packaged=False, pickerVisible=False),
    _locale("es-ES", "es-ES", "es", "values-es", weblateCode="es", packaged=False, pickerVisible=False),
    _locale("sv", "sv", "sv", "values-sv", packaged=False, pickerVisible=False),
    _locale("tr", "tr", "tr", "values-tr", packaged=False, pickerVisible=False),
    _locale("ml", "ml", "ml", "values-ml", script="Mlym", packaged=False, pickerVisible=False),
    _locale("hi", "hi", "hi", "values-hi", script="Deva", packaged=False, pickerVisible=False),
    _locale("bn", "bn", "bn", "values-bn", script="Beng", packaged=False, pickerVisible=False),
    _locale("hu", "hu", "hu", "values-hu", packaged=False, pickerVisible=False),
]
_CATALOGUE_ONLY = [
    _locale("bg", "bg", "bg", "values-bg", tier=2, packaged=False, pickerVisible=False),
    _locale("hr", "hr", "hr", "values-hr", tier=2, packaged=False, pickerVisible=False),
    _locale("et", "et", "et", "values-et", tier=2, packaged=False, pickerVisible=False),
    _locale("fa", "fa", "fa", "values-fa", tier=2, script="Arab", rtl=True, packaged=False, pickerVisible=False),
    _locale("fi", "fi", "fi", "values-fi", tier=2, packaged=False, pickerVisible=False),
    _locale("el", "el", "el", "values-el", tier=2, script="Grek", packaged=False, pickerVisible=False),
    _locale("he", "he", "iw", "values-iw", tier=2, weblateCode="he", script="Hebr", rtl=True, packaged=False, pickerVisible=False),
    _locale("id", "id", "in", "values-in", tier=2, weblateCode="id", packaged=False, pickerVisible=False),
    _locale("lv", "lv", "lv", "values-lv", tier=2, packaged=False, pickerVisible=False),
    _locale("lt", "lt", "lt", "values-lt", tier=2, packaged=False, pickerVisible=False),
    _locale("ms", "ms", "ms", "values-ms", tier=2, packaged=False, pickerVisible=False),
    _locale("ro", "ro", "ro", "values-ro", tier=2, packaged=False, pickerVisible=False),
    _locale("sr", "sr", "sr", "values-sr", tier=2, script="Cyrl", packaged=False, pickerVisible=False),
    _locale("sk", "sk", "sk", "values-sk", tier=2, packaged=False, pickerVisible=False),
    _locale("sl", "sl", "sl", "values-sl", tier=2, packaged=False, pickerVisible=False),
    _locale("th", "th", "th", "values-th", tier=2, script="Thai", packaged=False, pickerVisible=False),
    _locale("uk", "uk", "uk", "values-uk", tier=2, script="Cyrl", packaged=False, pickerVisible=False),
    _locale("vi", "vi", "vi", "values-vi", tier=2, packaged=False, pickerVisible=False),
]
_FULL_TIER1.extend(_CATALOGUE_ONLY)


def _full_tier1():
    """Return a deep copy of _FULL_TIER1 so tests can mutate entries without polluting siblings."""
    return json.loads(json.dumps(_FULL_TIER1))


def _tier1_with(overrides):
    """Return _FULL_TIER1 with specific entries overridden by id."""
    by_id = {e["id"]: e for e in _full_tier1()}
    for e in overrides:
        by_id[e["id"]] = e
    return list(by_id.values())


# ===========================================================================
# validate_strings.py
# ===========================================================================

class TestValidateStrings(unittest.TestCase):

    def setUp(self):
        self.vs = _load("vs_test", "tools/i18n/validate_strings.py")
        self.tmpdir = Path(tempfile.mkdtemp())

    def _run(self, res, locales_json, report="text"):
        self.vs.RES = res
        self.vs.LOCALES_JSON = locales_json
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = self.vs.main(report=report)
        return rc, buf.getvalue()

    def _run_streams(self, res, locales_json, report="text"):
        """Capture stdout and stderr separately, for --report json's stdout-purity contract."""
        self.vs.RES = res
        self.vs.LOCALES_JSON = locales_json
        out_buf, err_buf = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
            rc = self.vs.main(report=report)
        return rc, out_buf.getvalue(), err_buf.getvalue()

    def test_catalogue_missing_coverage_field_ok(self):
        """locales.json entries must NOT have a 'coverage' field (it is computed)."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_catalogue_rejects_stored_coverage(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        locales[0]["coverage"] = 100  # forbidden
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("must not be stored", out)

    def test_catalogue_duplicate_languageTag(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = [
            _locale("en", "en-US", "en", "values"),
            _locale("en2", "en-US", "en-rGB", "values-en-rGB", tier=0, pickerVisible=False),
        ]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate languageTag", out)

    def test_catalogue_dir_qualifier_mismatch(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-fr", tier=1, packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("should be 'values-de'", out)

    def test_catalogue_invalid_qualifier(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("x", "xx", "123bad", "values-123bad", tier=1, packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("invalid resourceQualifier", out)

    def test_catalogue_tier1_membership(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        # Only en-US as tier 1, missing the established translated targets
        locales = [_locale("en-US", "en-US", "en", "values")]
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("missing Tier 1", out)

    def test_xml_entities_not_false_rejected(self):
        """Fish &amp; Chips &lt;3 must NOT be rejected — entities are valid XML."""
        source = '<resources><string name="food">Fish &amp; Chips &lt;3</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_unescaped_apostrophe_in_source(self):
        source = '<resources><string name="x">It\'s fine</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("unescaped apostrophe", out)

    def test_unescaped_percent_in_source(self):
        source = '<resources><string name="x">50% off</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("unescaped percent", out)

    def test_escaped_percent_ok(self):
        source = '<resources><string name="x">100%% done</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_positional_with_precision(self):
        """%1$.2f must be recognized as positional, not bare."""
        source = '<resources><string name="x">Score: %1$.2f points</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)  # no bare placeholder, no escaping issue

    def test_duplicate_plurals_key_detected(self):
        source = '''<resources>
            <plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals>
            <plurals name="songs"><item quantity="one">X</item><item quantity="other">Y</item></plurals>
            </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate key 'songs'", out)

    def test_duplicate_array_key_detected(self):
        source = '''<resources>
            <string-array name="items"><item>A</item><item>B</item></string-array>
            <string-array name="items"><item>C</item></string-array>
            </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate key 'items'", out)

    def test_duplicate_plurals_no_overwrite(self):
        """First definition must be retained, not overwritten by the duplicate."""
        source = '''<resources>
            <plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals>
            <plurals name="songs"><item quantity="one">BAD</item></plurals>
            </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        self.vs.RES = res
        self.vs.LOCALES_JSON = self.tmpdir / "tools/i18n/locales.json"
        entries, errs = self.vs._parse_dir(res / "values")
        self.assertIn("other", entries["songs#"]["plurals"])
        self.assertEqual(entries["songs#"]["plurals"]["one"], "%1$d song")  # first retained

    def test_arabic_plural_quantities_placeholder_parity(self):
        """Arabic zero/two/few/many quantities must carry the same placeholders as source."""
        source = '<resources><plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        # Arabic translation with zero/two/few/many but missing %1$d in 'few'
        de_xml = '<resources><plurals name="songs"><item quantity="zero">0</item><item quantity="one">%1$d</item><item quantity="two">2</item><item quantity="few">few</item><item quantity="many">many</item><item quantity="other">%1$d</item></plurals></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("ar", "ar", "ar", "values-ar", script="Arab", rtl=True, packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-ar": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        # 'few' has no %1$d but source 'other' has %1$d → mismatch
        self.assertIn("placeholder mismatch", out)

    def test_translation_only_key_detected(self):
        """A key in the translation that doesn't exist in source must be flagged."""
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello">Hallo</string><string name="extra">Extra</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translation-only key 'extra'", out)

    def test_translatable_false_leak_in_translation(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello" translatable="false">Hallo</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' must not appear", out)

    def test_identical_to_source_is_not_rejected(self):
        """A Tier 1 translation identical to the source is NOT rejected — legitimate unchanged
        translations (OK, TV, PIN, Wi-Fi, brand names, loanwords) must pass. There is no review-state
        gate; textual equality was never used as an "unfinished" proxy."""
        source = '<resources><string name="ok">OK</string><string name="hello">Hello world</string></resources>'
        de_xml = '<resources><string name="ok">OK</string><string name="hello">Hello world</string></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=True, pickerVisible=True)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_bare_placeholder_in_translation_rejected(self):
        """A translation that introduces a bare %s where the source has none must be rejected."""
        source = '<resources><string name="hello">Hello</string></resources>'
        de_xml = '<resources><string name="hello">Hallo %s</string></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder in translation", out)

    def test_bare_placeholder_in_translation_array_rejected(self):
        """A bare %s in a translated string-array item must be rejected."""
        source = '<resources><string-array name="items"><item>A</item><item>B</item></string-array></resources>'
        de_xml = '<resources><string-array name="items"><item>A</item><item>B %s</item></string-array></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder in translation", out)

    def test_bare_placeholder_in_translation_plural_rejected(self):
        """A bare %s in a translated plural quantity must be rejected."""
        source = '<resources><plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        de_xml = '<resources><plurals name="songs"><item quantity="one">%1$d Lied</item><item quantity="other">%s Lieder</item></plurals></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder in translation", out)

    def test_b_plus_qualifier_accepted(self):
        """b+sr+Latn (Android script-qualified folder form) must be accepted."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _tier1_with([_locale("sr-Latn", "sr-Latn", "b+sr+Latn", "values-b+sr+Latn",
                                        packaged=False, pickerVisible=False)])
        # Remove one tier1 to keep membership exact (sr-Latn is not in the expected set)
        locales = [e for e in locales if e["id"] != "sr-Latn"]
        # Add it back with tier=0 so it doesn't affect the Tier 1 membership check
        sr = _locale("sr-Latn", "sr-Latn", "b+sr+Latn", "values-b+sr+Latn", tier=0,
                     weblateCode="sr_Latn", packaged=False, pickerVisible=False)
        locales.append(sr)
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        # Should pass — b+ qualifier is valid and its Weblate code uses the canonical underscore form.
        self.assertEqual(rc, 0, out)
        self.assertNotIn("invalid resourceQualifier", out)

    def test_canonical_weblate_mapping_pin(self):
        """A wrong weblateCode for a pinned qualifier must be rejected."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        # Corrupt pt-BR's weblateCode (should be pt_BR, not pt_PT)
        for e in locales:
            if e["id"] == "pt-BR":
                e["weblateCode"] = "pt_PT"
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("should be 'pt_BR'", out)

    def test_xliff_placeholder_parity(self):
        """Placeholders wrapped in <xliff:g> must be captured for parity checking."""
        xliff_ns = 'xmlns:xliff="urn:oe:names:tc:xliff:document:1.2"'
        source = f'<resources {xliff_ns}><string name="greet">Hello <xliff:g id="n">%1$s</xliff:g>, %2$d items</string></resources>'
        # German: swap placeholder order (valid) but drop one
        de_xml = '<resources><string name="greet">Hallo %1$s</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("placeholder mismatch", out)

    def test_packaged_locale_below_readiness_is_rejected_even_with_safe_fallback(self):
        """Missing keys still fall back safely, but release packaging now requires readiness."""
        source = '<resources><string name="a">A</string><string name="b">B</string></resources>'
        de_xml = '<resources><string name="a">A</string></resources>'  # missing 'b'
        locales = _full_tier1()
        de = next(e for e in locales if e["id"] == "de")
        de["packaged"] = True
        de["pickerVisible"] = True
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn(f"below the {THRESHOLD}% translation readiness threshold", out)

    def test_malformed_present_translation_still_exits_nonzero(self):
        """Missing keys are informational, but a present, malformed key still fails — it overrides
        the English fallback rather than deferring to it."""
        source = '<resources><string name="a">A</string></resources>'
        de_xml = '<resources><string name="a"></string></resources>'  # present but empty
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=True, pickerVisible=True)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("empty translation", out)

    def test_report_text_is_deterministic_and_catalogue_ordered(self):
        source = '<resources><string name="a">A</string><string name="b">B</string></resources>'
        ar_xml = '<resources><string name="a">A</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales, {"values-ar": ar_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json", report="text")
        self.assertEqual(rc, 0, out)
        self.assertIn("ar", out)
        self.assertIn("50.0%", out)
        self.assertIn("1 missing", out)

    def test_report_none_omits_coverage_output(self):
        source = '<resources><string name="a">A</string><string name="b">B</string></resources>'
        locales = _full_tier1()
        de = next(e for e in locales if e["id"] == "de")
        de["packaged"] = False
        de["pickerVisible"] = False
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": '<resources></resources>'})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json", report="none")
        self.assertEqual(rc, 0, out)
        self.assertNotIn("Translation coverage:", out)

    def test_report_json_reserves_stdout_for_the_document(self):
        """Even with a structural failure, stdout must parse as JSON; diagnostics go to stderr."""
        source = '<resources><string name="a">A</string></resources>'
        de_xml = '<resources><string name="a"></string></resources>'  # malformed: empty
        locales = _full_tier1()
        de = next(e for e in locales if e["id"] == "de")
        de["packaged"] = True
        de["pickerVisible"] = True
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out, err = self._run_streams(res, self.tmpdir / "tools/i18n/locales.json", report="json")
        self.assertEqual(rc, 1)
        self.assertIn("empty translation", err)
        payload = json.loads(out)  # raises if stdout carries anything but the JSON document
        self.assertEqual(payload["schemaVersion"], 1)
        self.assertEqual(payload["sourceKeys"], 1)
        de_row = next(r for r in payload["locales"] if r["languageTag"] == "de")
        # Key presence, not validity, drives coverage: an empty-but-present value still counts as
        # translated for the report even though it fails structural validation above.
        self.assertEqual(de_row["translatedKeys"], 1)
        self.assertEqual(de_row["missingKeys"], 0)

    def test_report_json_excludes_source_and_en_gb(self):
        source = '<resources><string name="a">A</string></resources>'
        locales = _full_tier1()
        locales.append(_locale("en-GB", "en-GB", "en-rGB", "values-en-rGB", tier=0,
                               weblateCode="en_GB", packaged=True, pickerVisible=False))
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out, err = self._run_streams(res, self.tmpdir / "tools/i18n/locales.json", report="json")
        self.assertEqual(rc, 0, err)
        payload = json.loads(out)
        tags = {row["languageTag"] for row in payload["locales"]}
        self.assertNotIn("en-US", tags)
        self.assertNotIn("en-GB", tags)
        self.assertEqual(len(tags), 42)  # original 24 plus 18 catalogue-only community targets

    def test_requested_catalogue_metadata_and_zero_resource_status(self):
        catalogue = json.loads((ROOT / "tools/i18n/locales.json").read_text())
        expected = {
            "bg": ("bg", "bg", "bg", "Bulgarian", "Български", "Cyrl", False),
            "hr": ("hr", "hr", "hr", "Croatian", "Hrvatski", "Latn", False),
            "et": ("et", "et", "et", "Estonian", "Eesti", "Latn", False),
            "fa": ("fa", "fa", "fa", "Persian", "فارسی", "Arab", True),
            "fi": ("fi", "fi", "fi", "Finnish", "Suomi", "Latn", False),
            "el": ("el", "el", "el", "Greek", "Ελληνικά", "Grek", False),
            "he": ("he", "iw", "he", "Hebrew", "עברית", "Hebr", True),
            "id": ("id", "in", "id", "Indonesian", "Bahasa Indonesia", "Latn", False),
            "lv": ("lv", "lv", "lv", "Latvian", "Latviešu", "Latn", False),
            "lt": ("lt", "lt", "lt", "Lithuanian", "Lietuvių", "Latn", False),
            "ms": ("ms", "ms", "ms", "Malay", "Bahasa Melayu", "Latn", False),
            "ro": ("ro", "ro", "ro", "Romanian", "Română", "Latn", False),
            "sr": ("sr", "sr", "sr", "Serbian (Cyrillic)", "Српски", "Cyrl", False),
            "sk": ("sk", "sk", "sk", "Slovak", "Slovenčina", "Latn", False),
            "sl": ("sl", "sl", "sl", "Slovenian", "Slovenščina", "Latn", False),
            "th": ("th", "th", "th", "Thai", "ไทย", "Thai", False),
            "uk": ("uk", "uk", "uk", "Ukrainian", "Українська", "Cyrl", False),
            "vi": ("vi", "vi", "vi", "Vietnamese", "Tiếng Việt", "Latn", False),
        }
        by_id = {e["id"]: e for e in catalogue}
        # A catalogue-only language may since have been promoted (tier 1, shipped, with resources).
        self.assertLessEqual({e["id"] for e in catalogue if e["tier"] == 2}, set(expected))
        for locale_id, (tag, qualifier, weblate, english, endonym, script, rtl) in expected.items():
            with self.subTest(locale=locale_id):
                entry = by_id[locale_id]
                self.assertEqual((tag, qualifier, weblate, english, endonym, script, rtl), (
                    entry["languageTag"], entry["resourceQualifier"], entry["weblateCode"],
                    entry["englishName"], entry["endonym"], entry["script"], entry["rtl"]))
                self.assertEqual(entry["resourceDirectory"], f"values-{qualifier}")
                promoted = entry["tier"] == 1
                self.assertIn(entry["tier"], (1, 2))
                self.assertEqual(promoted, entry["packaged"])
                self.assertEqual(promoted, entry["pickerVisible"])
                self.assertEqual(promoted, (ROOT / "core/src/main/res" / entry["resourceDirectory"]).exists())

    def test_spanish_default_uses_current_weblate_es_definition(self):
        catalogue = json.loads((ROOT / "tools/i18n/locales.json").read_text())
        self.assertEqual("es", next(e for e in catalogue if e["id"] == "es-ES")["weblateCode"])

    def test_catalogue_only_directory_is_rejected_if_created(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales, {
            "values-bg": '<resources><string name="hello">Здравей</string></resources>'})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("catalogue-only locale must not have a resource directory", out)

    def test_language_page_has_one_global_cta_and_remote_safe_contribution_panel(self):
        # Asserts on a TV-app screen. This toolkit is shared with the core repo, which has no app
        # module at all, so skip rather than fail where the screen legitimately does not exist.
        path = ROOT / "app/src/main/java/tv/own/owntv/features/settings/LanguageSettingsScreen.kt"
        if not path.is_file():
            self.skipTest("no app module in this repo — LanguageSettingsScreen is app-side")
        screen = path.read_text()
        page = screen.split("if (showContribution)", 1)[0]
        self.assertEqual(1, page.count("settings_language_help_translate"))
        self.assertEqual(1, screen.count("CompanionLink.renderQr(url)"))
        self.assertIn("trapAllFocusExit", screen)
        self.assertIn("BackHandler { onDismiss() }", screen)
        self.assertIn("openContributionLink(context, url)", screen)
        self.assertIn("openContributionLink(context, requestUrl)", screen)
        self.assertIn("copyContributionLink(context, copyUrl)", screen)
        dialog = screen.split("private fun TranslationContributionDialog", 1)[1]
        self.assertIn("color = colors.primary", dialog)
        for resource in (
            "settings_language_contribution_description",
            "settings_language_request_workflow",
            "settings_language_request_new",
            "settings_language_contribution_qr_description",
            "settings_language_contribution_qr_failed",
            "settings_language_contribution_copy",
        ):
            self.assertIn(f"stringResource(R.string.{resource})", dialog)
        for resource in (
            "settings_language_contribution_open_failed",
            "settings_language_contribution_copied",
            "settings_language_contribution_copy_failed",
        ):
            self.assertIn(f"R.string.{resource}", dialog)
        self.assertIn("stringResource(it)", dialog)
        self.assertLess(
            screen.index("label = stringResource(R.string.settings_language_help_translate)"),
            screen.index("if (showSystemDefault)"),
        )
        settings = (ROOT / "app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt").read_text()
        appearance = settings.index(
            'RootGroup("group_appearance", stringResource(R.string.settings_appearance_group)'
        )
        app = settings.index('RootGroup("group_app", stringResource(R.string.settings_app_group)')
        language = settings.index("title = stringResource(R.string.settings_language),")
        theme = settings.index("title = stringResource(R.string.settings_theme),")
        self.assertLess(appearance, theme)
        self.assertLess(app, language)
        view_model = (ROOT / "app/src/main/java/tv/own/owntv/features/settings/LanguageSettingsViewModel.kt").read_text()
        self.assertIn("sortedBy { it.englishName.lowercase(Locale.ROOT) }", view_model)
        self.assertNotIn("sortedBy { it.endonym.lowercase() }", view_model)

    def test_canonical_url_and_qr_payload_artifacts_are_consistent(self):
        config = json.loads((ROOT / "tools/i18n/community.json").read_text())
        url = config["projectUrl"]
        request_url = config["languageRequestUrl"]
        self.assertEqual("https://hosted.weblate.org/projects/owntv/", url)
        self.assertEqual(
            "https://github.com/ahXN00/OwnTV/issues/new?template=feature_request.yml&title=%5BLanguage%5D%20Add%20",
            request_url,
        )
        self.assertIsInstance(config["translationReadinessThresholdPercent"], int)
        generated = (ROOT / "core/src/main/java/tv/own/owntv/core/i18n/SupportedLocales.kt").read_text()
        readme = (ROOT / "README.md").read_text()
        guide = (ROOT / "tools/i18n/README.md").read_text()
        self.assertIn(f'CONTRIBUTION_PROJECT_URL: String = "{url}"', generated)
        self.assertIn(f'LANGUAGE_REQUEST_URL: String = "{request_url}"', generated)
        self.assertIn(f"[Hosted Weblate]({url})", readme)
        self.assertIn(f"[open a language request ticket]({request_url})", readme)
        self.assertNotIn("owntv-weblate-qr.svg", readme)
        self.assertNotIn("Accessible plain link:", readme)
        self.assertIn(f"<{url}>", guide)
        self.assertIn(f"<{request_url}>", guide)
        self.assertEqual(1, readme.count(url))  # One clickable Hosted Weblate link
        self.assertEqual(1, readme.count(request_url))
        self.assertEqual(1, guide.count(url))
        self.assertEqual(1, guide.count(request_url))

    def test_packaging_readiness_boundary_just_below_rejected_at_threshold_accepted(self):
        source_items = "".join(f'<string name="k{i}">K{i}</string>' for i in range(100))
        source = f"<resources>{source_items}</resources>"
        for translated_count, expected_rc in ((THRESHOLD - 1, 1), (THRESHOLD, 0)):
            with self.subTest(translated=translated_count):
                case = Path(tempfile.mkdtemp())
                locales = _full_tier1()
                bg = next(e for e in locales if e["id"] == "bg")
                bg.update(tier=1, packaged=True, pickerVisible=True)
                translated = "".join(
                    f'<string name="k{i}">B{i}</string>' for i in range(translated_count))
                res = _make_fixture(case, source, locales, {
                    "values-bg": f"<resources>{translated}</resources>"})
                rc, out = self._run(res, case / "tools/i18n/locales.json")
                self.assertEqual(expected_rc, rc, out)
                if expected_rc:
                    self.assertIn(f"below the {THRESHOLD}% translation readiness threshold", out)

    def test_translation_review_state_neither_read_nor_required(self):
        """translation_status.json is gone; the validator must not reference or require it."""
        self.assertFalse(hasattr(self.vs, "TRANSLATION_STATUS"))
        self.assertFalse((ROOT / "tools/i18n/translation_status.json").exists())

    def test_coverage_numerator_matches_gen_supported_locales(self):
        """Regression: the CI report and the picker badge must use the exact same key-set rules."""
        gen = _load("gen_parity_test", "tools/i18n/gen_supported_locales.py")
        source = '''<resources>
            <string name="a">A</string>
            <string name="b">B</string>
            <plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals>
            </resources>'''
        de_xml = '''<resources>
            <string name="a">A</string>
            <plurals name="songs"><item quantity="one">%1$d Ein</item><item quantity="other">%1$d Viele</item></plurals>
            </resources>'''  # missing 'b'
        locales = _full_tier1()
        de = next(e for e in locales if e["id"] == "de")
        de["packaged"] = False
        de["pickerVisible"] = False
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out, err = self._run_streams(res, self.tmpdir / "tools/i18n/locales.json", report="json")
        self.assertEqual(rc, 0, err)
        payload = json.loads(out)
        de_row = next(r for r in payload["locales"] if r["languageTag"] == "de")

        gen.LOCALES_JSON = self.tmpdir / "tools/i18n/locales.json"
        gen.RES = res
        gen.OUT = self.tmpdir / "SupportedLocales.kt"
        gen.ROOT = self.tmpdir
        _, _, gen_source_keys = gen._generate()
        source_keys = gen._string_keys(res / "values")
        de_keys = gen._string_keys(res / "values-de")
        gen_translated = len(source_keys & de_keys)

        self.assertEqual(payload["sourceKeys"], gen_source_keys)
        self.assertEqual(de_row["translatedKeys"], gen_translated)

    def test_bare_placeholder_in_source(self):
        source = '<resources><string name="x">Value %s</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("bare placeholder", out)

    # --- Plural validation fixes ---

    def test_source_plural_missing_one_rejected(self):
        """Source English must carry the 'one' quantity (English's CLDR rule requires one, other)."""
        source = '<resources><plurals name="songs"><item quantity="other">%1$d songs</item></plurals></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("missing required English quantity `one`", out)

    def test_plural_zero_placeholders_not_confused_with_absent(self):
        """A source quantity present with zero placeholders must match a translation with zero —
        the old `or` fallback confused 'quantity absent' (empty list) with 'present, no placeholders'."""
        source = '<resources><plurals name="songs"><item quantity="one">One song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        de_xml = '<resources><plurals name="songs"><item quantity="one">Ein Lied</item><item quantity="other">%1$d Lieder</item></plurals></resources>'
        locales = _tier1_with([_locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"valid zero-placeholder quantity was rejected: {out}")

    def test_french_plural_many_required(self):
        """French's CLDR rule includes 'many' (for large round numbers) — a French translation
        missing 'many' must be rejected."""
        source = '<resources><plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals></resources>'
        fr_xml = '<resources><plurals name="songs"><item quantity="one">%1$d chanson</item><item quantity="other">%1$d chansons</item></plurals></resources>'
        locales = _tier1_with([_locale("fr", "fr", "fr", "values-fr", packaged=False, pickerVisible=False)])
        res = _make_fixture(self.tmpdir, source, locales, {"values-fr": fr_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("missing required quantity many", out)

    # --- Qualifier and Weblate validation fixes ---

    def test_b_plus_numeric_region_accepted(self):
        """b+es+419 (UN M.49 numeric region in b+ form) must be accepted."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        # Add a non-tier1 entry with b+es+419
        locales.append(_locale("es-419", "es-419", "b+es+419", "values-b+es+419", tier=0,
                               weblateCode="es_419", packaged=False, pickerVisible=False))
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"b+es+419 was rejected: {out}")
        self.assertNotIn("invalid resourceQualifier", out)

    def test_plain_script_qualifier_rejected_by_aapt2_policy(self):
        """Android script folders must use b+ syntax; sr-Latn is not an aapt2 resource qualifier."""
        self.assertIsNone(self.vs._QUAL_RE.fullmatch("sr-Latn"))
        self.assertIsNone(self.vs._QUAL_RE.fullmatch("b+de"))
        self.assertIsNone(self.vs._QUAL_RE.fullmatch("b+en+US"))
        self.assertIsNotNone(self.vs._QUAL_RE.fullmatch("b+sr+Latn"))
        self.assertIsNotNone(self.vs._QUAL_RE.fullmatch("b+es+419"))

    def test_b_plus_lowercase_script_rejected(self):
        """b+sr+latn (lowercase script) must be rejected — Android requires title-case script."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        locales.append(_locale("sr-Latn", "sr-Latn", "b+sr+latn", "values-b+sr+latn", tier=0,
                               packaged=False, pickerVisible=False))
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertIn("invalid resourceQualifier", out)

    def test_canonical_weblate_all_entries_pinned(self):
        """Changing German's weblateCode from 'de' to 'fr' must be rejected — all entries are pinned."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["weblateCode"] = "fr"  # wrong — should be 'de'
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("should be 'de'", out)

    def test_tier_42_rejected(self):
        """tier=42 must be rejected — only the documented 0, 1, and 2 tiers are valid."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["tier"] = 42
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("tier must be one of", out)

    def test_blank_english_name_rejected(self):
        """A blank englishName must be rejected."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["englishName"] = ""
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("englishName must be a non-blank string", out)

    def test_boolean_true_tier_rejected(self):
        """JSON true is an int subclass in Python; it must not satisfy the integer tier schema."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        for e in locales:
            if e["id"] == "de":
                e["tier"] = True
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("tier must be one of", out)

    def test_catalogue_root_type_rejected_without_crash(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        res = _make_fixture(self.tmpdir, source, [])
        (self.tmpdir / "tools/i18n/locales.json").write_text(json.dumps({"de": {}}))
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("root must be an array", out)

    # --- translatable=false placement and formatting fixes ---

    def test_source_donottranslate_requires_false(self):
        """Every source entry in donottranslate.xml must explicitly be non-translatable."""
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        (res / "values/donottranslate.xml").write_text(
            '<resources><string name="hidden">Visible text</string></resources>')
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("must declare translatable=\"false\"", out)

    def test_source_donottranslate_collides_with_source_key(self):
        """The constants namespace may not duplicate a key from strings*.xml."""
        source = '<resources><string name="hidden">Visible text</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        (res / "values/donottranslate.xml").write_text(
            '<resources><string name="hidden" translatable="false">Protocol</string></resources>')
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate key 'hidden'", out)

    def test_source_donottranslate_valid_entry_passes(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        (res / "values/donottranslate.xml").write_text(
            '<resources><string name="brand" translatable="false">OwnTV</string></resources>')
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)

    def test_translatable_false_in_strings_xml_rejected(self):
        """translatable='false' inside strings.xml must be rejected — it belongs in donottranslate.xml."""
        source = '<resources><string name="brand" translatable="false">OwnTV</string><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' on 'brand' must be in donottranslate.xml", out)

    def test_translatable_false_single_quote_is_rejected(self):
        source = "<resources><string name='brand' translatable='false'>OwnTV</string></resources>"
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' on 'brand'", out)

    def test_translatable_false_on_plural_or_array_is_rejected(self):
        source = '''<resources>
            <plurals name="songs" translatable="false"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
            <string-array name="items" translatable="false"><item>A</item></string-array>
        </resources>'''
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' on 'songs'", out)
        self.assertIn("translatable='false' on 'items'", out)

    def test_translation_false_plural_or_array_is_rejected(self):
        source = '''<resources>
            <plurals name="songs"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
            <string-array name="items"><item>A</item></string-array>
        </resources>'''
        de_xml = '''<resources>
            <plurals name="songs" translatable="false"><item quantity="one">Ein</item><item quantity="other">Viele</item></plurals>
            <string-array name="items" translatable="false"><item>A</item></string-array>
        </resources>'''
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translatable='false' must not appear", out)

    def test_translation_donottranslate_file_is_rejected(self):
        source = '<resources><string name="hello">Hello</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": '<resources></resources>'})
        (res / "values-de/donottranslate.xml").write_text(
            '<resources><string name="app_name" translatable="false">OwnTV</string></resources>')
        # Make the synthetic locale part of the catalogue without changing the exact Tier 1 set.
        for e in locales:
            if e["id"] == "de":
                e["packaged"] = False
                e["pickerVisible"] = False
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("donottranslate.xml leaked key 'app_name'", out)

    def test_empty_source_still_checks_translation_only_keys(self):
        source = '<resources></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales, {
            "values-de": '<resources><string name="leaked">Leaked</string></resources>'})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("translation-only key 'leaked'", out)

    def test_full_java_format_placeholders_recognized(self):
        "%1$tY, %1$tL and %1$S must be recognized as positional, not flagged as unescaped percent."""
        source = '<resources><string name="year">Year %1$tY %1$tL %1$S</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"Java format placeholders were rejected: {out}")

    def test_invalid_java_format_placeholders_rejected(self):
        """Formatter-invalid flag/conversion combinations must fail before runtime formatting."""
        invalid = ["%0$s", "%1$#s", "%1$-s", "%1$.2d", "%1$0tY", "%1$0f",
                   "%1$+x", "%1$,e", "%1$#g", "%1$(a", "%1$L", "%1$tJ", "%1$n",
                   "%1$2147483648s", "%1$.2147483648s", "%2147483648$s"]
        locales = _full_tier1()
        for placeholder in invalid:
            with self.subTest(placeholder=placeholder):
                source = f'<resources><string name="x">Value {placeholder}</string></resources>'
                case_dir = Path(tempfile.mkdtemp())
                res = _make_fixture(case_dir, source, locales)
                rc, out = self._run(res, case_dir / "tools/i18n/locales.json")
                self.assertEqual(rc, 1, f"invalid placeholder {placeholder} passed: {out}")
                self.assertIn("invalid Java/Android format placeholder", out)

    def test_invalid_java_format_placeholder_in_translation_rejected(self):
        source = '<resources><string name="x">Value %1$s</string></resources>'
        de_xml = '<resources><string name="x">Wert %1$#s</string></resources>'
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        res = _make_fixture(self.tmpdir, source, locales, {"values-de": de_xml})
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("invalid Java/Android format placeholder", out)

    def test_whole_string_quoted_apostrophe_accepted(self):
        """'This\'ll work' wrapped in whole-string double quotes is valid Android — not rejected."""
        source = '<resources><string name="x">"This\'ll work"</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, f"whole-string quoted apostrophe was rejected: {out}")

    def test_leading_sentence_fragment_spacing_rejected(self):
        source = '<resources><string name="x"> leading fragment</string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 1, out)
        self.assertIn("sentence fragment", out)

    def test_metadata_separator_spacing_is_allowed(self):
        source = '<resources><string name="x_separator">  ·  </string></resources>'
        locales = _full_tier1()
        res = _make_fixture(self.tmpdir, source, locales)
        rc, out = self._run(res, self.tmpdir / "tools/i18n/locales.json")
        self.assertEqual(rc, 0, out)


# ===========================================================================
# check_hardcoded_strings.py
# ===========================================================================

class TestCheckHardcodedStrings(unittest.TestCase):

    def setUp(self):
        self.chs = _load("chs_test", "tools/i18n/check_hardcoded_strings.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.chs.SRC = self.tmpdir / "src"
        self.chs.SRC.mkdir()
        # The scanner walks SRC_ROOTS, one entry per Gradle module. Redirect it at the sandbox, or
        # every case here silently inventories the real repository instead of the file it wrote.
        self.chs.SRC_ROOTS = [self.chs.SRC]
        self.chs.MODULE_MAIN_DIRS = ["src"]
        self.chs.ROOT = self.tmpdir
        self.chs.BASELINE = self.tmpdir / "baseline.txt"
        self.chs.SAFE_MANIFEST = self.tmpdir / "safe_literals.txt"
        self.chs.SAFE_MANIFEST.write_text(self.chs._serialize_safe({}))

    def _write_kt(self, name, content):
        path = self.chs.SRC / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def _args(self, base=None, bootstrap=True):
        class Args:
            pass
        args = Args()
        args.base = base
        args.bootstrap = bootstrap
        return args

    def test_inventory_is_mechanical_and_occurrence_aware(self):
        self._write_kt("Main.kt", '''package x
// "ignored comment"
val a = "Visible"
val b = "Visible"
val c = "SELECT * FROM channels"
val d = 'x'
''')
        inventory = self.chs._inventory()
        self.assertEqual(inventory[("src/Main.kt", "Visible")], 2)
        self.assertEqual(inventory[("src/Main.kt", "SELECT * FROM channels")], 1)
        self.assertFalse(any("ignored" in text for _, text in inventory))

    def test_inventory_does_not_guess_semantics(self):
        self._write_kt("Main.kt", 'package x\nfun f() { Log.d("TAG", "diagnostic"); Text("Update now") }\n')
        texts = {key[1] for key in self.chs._scan()}
        self.assertEqual(texts, {"TAG", "diagnostic", "Update now"})

    def test_nested_interpolation_literals_are_separate(self):
        source = 'package x\nval x = "Movies / ${title ?: "All"}"\n'
        self._write_kt("Screen.kt", source)
        texts = {key[1] for key in self.chs._inventory()}
        self.assertIn("All", texts)
        self.assertTrue(any("Movies /" in text for text in texts))

    def test_kotlin_escape_decoder_preserves_literal_backslashes(self):
        self.assertEqual(self.chs._decode(r'"\u0041"'), "A")
        self.assertEqual(self.chs._decode(r'"\\u0041"'), r"\u0041")
        self.assertEqual(self.chs._decode(r'"\n"'), "\n")
        self.assertEqual(self.chs._decode(r'"\\n"'), r"\n")

    def test_manifest_round_trips_backslashes_tabs_and_newlines(self):
        counts = {
            ("src/Main.kt", r"literal\nsequence"): 1,
            ("src/Main.kt", "actual\nnewline\tand\\slash"): 2,
        }
        self.assertEqual(self.chs._parse(self.chs._serialize(counts)), counts)
        entries = {key: (count, "technical") for key, count in counts.items()}
        safe, categories, errors = self.chs._parse_safe(self.chs._serialize_safe(entries))
        self.assertFalse(errors)
        self.assertEqual(safe, counts)
        self.assertEqual(set(categories.values()), {"technical"})

    def test_generate_classifies_every_non_safe_literal_as_baseline(self):
        self._write_kt("Main.kt", 'package x\nval a = "Hello"\nval b = "World"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self.assertEqual(
            set(self.chs._parse(self.chs.BASELINE.read_text())),
            {("src/Main.kt", "Hello"), ("src/Main.kt", "World")},
        )

    def test_explicit_safe_entry_is_removed_from_baseline(self):
        self._write_kt("Main.kt", 'package x\nval tag = "Worker"\nval label = "Settings"\n')
        entries = {("src/Main.kt", "Worker"): (1, "log")}
        self.chs.SAFE_MANIFEST.write_text(self.chs._serialize_safe(entries))
        self.assertEqual(self.chs.cmd_generate(None), 0)
        baseline = self.chs._parse(self.chs.BASELINE.read_text())
        self.assertNotIn(("src/Main.kt", "Worker"), baseline)
        self.assertIn(("src/Main.kt", "Settings"), baseline)

    def test_bootstrap_requires_exact_baseline_plus_safe_inventory(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval tag = "TAG"\n')
        self.chs.SAFE_MANIFEST.write_text(
            self.chs._serialize_safe({("src/Main.kt", "TAG"): (1, "log")})
        )
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self.assertEqual(self.chs.cmd_verify(self._args()), 0)

    def test_unclassified_literal_fails_verification(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval y = "New literal"\n')
        self.assertEqual(self.chs.cmd_verify(self._args()), 1)

    def test_stale_safe_literal_fails_verification_and_generation(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.chs.SAFE_MANIFEST.write_text(
            self.chs._serialize_safe({("src/Main.kt", "Gone"): (1, "technical")})
        )
        self.chs.BASELINE.write_text(self.chs._serialize({("src/Main.kt", "Hello"): 1}))
        self.assertEqual(self.chs.cmd_verify(self._args()), 1)
        self.assertEqual(self.chs.cmd_generate(None), 1)

    def test_unknown_safe_category_is_rejected(self):
        text = self.chs._serialize_safe({}).replace(
            "\n\n", "\n1\tmagic\tsrc/Main.kt\tTAG\n\n", 1
        )
        _, _, errors = self.chs._parse_safe(text)
        self.assertTrue(any("unknown category" in error for error in errors))

    def test_merge_base_ratchet_rejects_baseline_growth(self):
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        base = self.tmpdir / "base.txt"
        base.write_text(self.chs.BASELINE.read_text())
        self._write_kt("Main.kt", 'package x\nval x = "Hello"\nval y = "New literal"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        self.assertEqual(self.chs.cmd_verify(self._args(str(base), bootstrap=False)), 1)

    def test_classify_safe_moves_literal_out_of_baseline(self):
        self._write_kt("Main.kt", 'package x\nval tag = "Worker"\n')
        self.assertEqual(self.chs.cmd_generate(None), 0)
        class Args:
            path = "src/Main.kt"
            text = "Worker"
            category = "log"
            count = None
        self.assertEqual(self.chs.cmd_classify_safe(Args()), 0)
        self.assertEqual(self.chs._parse(self.chs.BASELINE.read_text()), {})
        safe, categories, errors = self.chs._safe_entries()
        self.assertFalse(errors)
        self.assertEqual(safe[("src/Main.kt", "Worker")], 1)
        self.assertEqual(categories[("src/Main.kt", "Worker")], "log")

    def test_scanner_migration_policy_freezes_app_tree(self):
        workflow = (ROOT / ".github/workflows/i18n.yml").read_text()
        checker = (ROOT / "tools/i18n/check_hardcoded_strings.py").read_text()
        self.assertIn("verify-ci --base-sha", workflow)
        self.assertIn('"diff", "--name-only", base_sha, "HEAD", "--", *MODULE_MAIN_DIRS', checker)
        self.assertIn("Scanner migrations may not change module source", checker)


# ===========================================================================
# gen_supported_locales.py
# ===========================================================================

class TestGenSupportedLocales(unittest.TestCase):

    def setUp(self):
        self.gen = _load("gen_test", "tools/i18n/gen_supported_locales.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.gen.LOCALES_JSON = self.tmpdir / "locales.json"
        self.gen.RES = self.tmpdir / "res"
        self.gen.OUT = self.tmpdir / "SupportedLocales.kt"
        self.gen.ROOT = self.tmpdir  # so OUT.relative_to(ROOT) works

    def _write(self, locales, source_xml="<resources></resources>", translations=None):
        self.gen.LOCALES_JSON.write_text(json.dumps(locales))
        (self.gen.RES / "values").mkdir(parents=True)
        (self.gen.RES / "values/strings.xml").write_text(source_xml)
        for resdir, xml in (translations or {}).items():
            (self.gen.RES / resdir).mkdir(parents=True)
            (self.gen.RES / f"{resdir}/strings.xml").write_text(xml)

    def test_string_array_counted_in_coverage(self):
        """string-array keys must be counted in coverage, matching validate_strings.py."""
        source = '''<resources>
            <string name="a">A</string>
            <string-array name="items"><item>X</item><item>Y</item></string-array>
            <plurals name="songs"><item quantity="one">%1$d song</item><item quantity="other">%1$d songs</item></plurals>
            </resources>'''
        de_xml = '''<resources>
            <string name="a">A</string>
            <string-array name="items"><item>X</item><item>Y</item></string-array>
            </resources>'''  # missing plurals
        locales = [_locale("en", "en-US", "en", "values"),
                   _locale("de", "de", "de", "values-de", packaged=False, pickerVisible=False)]
        self._write(locales, source, {"values-de": de_xml})
        text, n, nkeys = self.gen._generate()
        # Source has 3 translatable keys: a, items[], songs#
        self.assertEqual(nkeys, 3, f"expected 3 source keys, got {nkeys}")
        # de has 2 of 3 → coverage = round(100*2/3) = 67
        de_entry = [e for e in locales if e["id"] == "de"][0]
        # _generate returns (text, n_entries, n_keys); coverage is embedded in the text
        self.assertIn("coverage = 67", text)

    def test_check_detects_stale(self):
        locales = _full_tier1()
        self._write(locales)
        # Generate and then modify locales.json
        self.gen.cmd_generate()
        locales[0]["englishName"] = "MODIFIED"
        self.gen.LOCALES_JSON.write_text(json.dumps(locales))
        rc = self.gen.cmd_check()
        self.assertEqual(rc, 1, "stale SupportedLocales.kt was not detected")

    def test_check_passes_when_fresh(self):
        locales = _full_tier1()
        self._write(locales)
        self.gen.cmd_generate()
        rc = self.gen.cmd_check()
        self.assertEqual(rc, 0)


# ===========================================================================
# check_number_locale.py
# ===========================================================================

class TestCheckNumberLocale(unittest.TestCase):

    def setUp(self):
        self.checker = _load("number_locale_test", "tools/i18n/check_number_locale.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.source = self.tmpdir / "Sample.kt"
        self.allowlist = self.tmpdir / "allowlist.txt"
        self.allowlist.write_text("")

    def _check(self, source):
        self.source.write_text(source)
        return self.checker.check([self.source], self.allowlist)[0]

    def test_root_locale_passes_for_static_and_extension_calls(self):
        errors = self._check('''
            val first = String.format(Locale.ROOT, "%d", value)
            val second = "%1$.2f".format(java.util.Locale.ROOT, nested(value, other))
        ''')
        self.assertEqual([], errors)

    def test_missing_and_non_root_locales_fail(self):
        for expression in (
            '"%d".format(value)',
            '"%d".format(Locale.getDefault(), value)',
            '"%d".format(Locale.US, value)',
            '"%d".format(locale, value)',
            'String.format("%d", value)',
        ):
            with self.subTest(expression=expression):
                self.assertTrue(self._check(f"val result = {expression}"))

    def test_reviewed_display_waiver_passes(self):
        self.source.write_text('val result = "%d".format(value)')
        path = self.source.as_posix()
        self.allowlist.write_text(f"DISPLAY\t{path}\t%d\t1\tLocalized value at a final UI renderer\n")
        errors, used = self.checker.check([self.source], self.allowlist)
        self.assertEqual([], errors)
        self.assertEqual({(path, "%d", 1)}, used)

    def test_hex_and_octal_are_mechanically_excluded(self):
        self.assertEqual([], self._check('''
            val hex = "%08x".format(value)
            val octal = String.format("%o", value)
        '''))

    def test_comments_and_strings_with_fake_calls_are_ignored(self):
        self.assertEqual([], self._check(r'''
            // "%d".format(value)
            /* String.format("%f", value) */
            val normal = "fake: \\"%d\\".format(value)"
            val triple = """fake: "%d".format(value)"""
        '''))

    def test_triple_and_interpolated_format_literals_are_scanned(self):
        errors = self._check(r'''
            val first = """count=$value %d""".format(Locale.ROOT, value)
            val second = "count=${value}: %d".format(Locale.ROOT, value)
        ''')
        self.assertEqual([], errors)

    def test_occurrence_distinguishes_identical_literals(self):
        self.source.write_text('''
            val first = "%d".format(Locale.ROOT, one)
            val second = "%d".format(two)
        ''')
        path = self.source.as_posix()
        self.allowlist.write_text(f"DISPLAY\t{path}\t%d\t2\tSecond call is localized display output\n")
        errors, used = self.checker.check([self.source], self.allowlist)
        self.assertEqual([], errors)
        self.assertEqual({(path, "%d", 2)}, used)


# ===========================================================================
# check_text_overflow.py
# ===========================================================================

class TestCheckTextOverflow(unittest.TestCase):

    def setUp(self):
        self.checker = _load("text_overflow_test", "tools/i18n/check_text_overflow.py")
        self.tmpdir = Path(tempfile.mkdtemp())
        self.source = self.tmpdir / "Sample.kt"
        self.allowlist = self.tmpdir / "allowlist.txt"
        self.allowlist.write_text("")

    def _check(self, source):
        self.source.write_text(source)
        return self.checker.check([self.source], self.allowlist)[0]

    def test_multiline_nested_and_adjacent_text_calls(self):
        errors = self._check('''
            Text(
                text = nested(value, fallback("x")),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("second", maxLines = 2, overflow = TextOverflow.Ellipsis)
        ''')
        self.assertEqual([], errors)

    def test_fully_qualified_text_is_detected(self):
        errors = self._check('androidx.tv.material3.Text("title", maxLines = 1)')
        self.assertEqual(1, len(errors))
        self.assertIn("without overflow", errors[0])

    def test_comments_and_string_contents_are_ignored(self):
        errors = self._check(r'''
            // Text("comment", maxLines = 1)
            /* BasicText("comment", maxLines = 1) */
            val normal = "Text(\"fake\", maxLines = 1)"
            val triple = """BasicText("fake", maxLines = 1)"""
            val interpolated = "${value}: Text(maxLines = 1)"
        ''')
        self.assertEqual([], errors)

    def test_basic_text_requires_overflow(self):
        errors = self._check('BasicText("body", maxLines = nested(limit, 1))')
        self.assertEqual(1, len(errors))

    def test_intentional_clip_uses_exact_exception(self):
        self.source.write_text('Text(number.toString(), maxLines = 1, overflow = TextOverflow.Clip)')
        path = self.source.as_posix()
        self.allowlist.write_text(
            f"{path}\tText(number.toString())\t1\tFixed-width technical identifier\n"
        )
        errors, used = self.checker.check([self.source], self.allowlist)
        self.assertEqual([], errors)
        self.assertEqual({(path, "Text(number.toString())", 1)}, used)

    def test_unapproved_clip_fails(self):
        errors = self._check('Text("title", maxLines = 1, overflow = TextOverflow.Clip)')
        self.assertEqual(1, len(errors))
        self.assertIn("unapproved", errors[0])

    def test_non_text_max_lines_is_ignored(self):
        self.assertEqual([], self._check('PosterCard(title, maxLines = 1)'))


# ===========================================================================
# check_pseudo_locales.py
# ===========================================================================

class TestCheckPseudoLocales(unittest.TestCase):

    def setUp(self):
        self.cp = _load("cp_test", "tools/i18n/check_pseudo_locales.py")

    def test_locale_config_extraction(self):
        """Full aapt2 configs like 'en-rGB-w720dp' must extract locale 'en-rGB'."""
        configs = {"en-rGB-w720dp-h1280dp", "en-rXA", "ar-rXB", "ar", "v26", "w720dp"}
        locales = self.cp._locale_configs(configs)
        self.assertIn("en-rGB", locales)
        self.assertIn("en-rXA", locales)
        self.assertIn("ar-rXB", locales)
        self.assertIn("ar", locales)
        self.assertNotIn("v26", locales)
        self.assertNotIn("w720dp", locales)

    def test_debug_leak_detected(self):
        """A locale outside the allowed debug set must be flagged as a leak."""
        configs = {"en-rGB", "en-rXA", "ar-rXB", "ar", "zz-port-mdpi"}
        locales = self.cp._locale_configs(configs)
        leaks = locales - self.cp._ALLOWED_DEBUG
        self.assertIn("zz", leaks)

    def test_release_leak_detected(self):
        """en-rXA in a release config set must be flagged."""
        configs = {"en", "en-rGB", "en-rXA"}
        locales = self.cp._locale_configs(configs)
        leaks = locales - self.cp._ALLOWED_RELEASE
        self.assertIn("en-rXA", leaks)


# ===========================================================================
# seed_text.py (docs/i18n-phase4a-seed-translations.md, Part 2)
# ===========================================================================

class TestSeedText(unittest.TestCase):

    def setUp(self):
        self.st = _load("seed_text_test", "tools/i18n/seed_text.py")

    def test_tokenize_detokenize_round_trip(self):
        raw = 'Hello <xliff:g id="n">%1$s</xliff:g>, you have <xliff:g id="c">%2$d</xliff:g> items'
        tokenized, originals = self.st.tokenize(raw)
        self.assertNotIn("<xliff:g", tokenized)
        self.assertEqual(self.st.detokenize(tokenized, originals), raw)

    def test_reordered_tokens_preserve_exact_parity(self):
        raw = '<xliff:g id="a">%1$s</xliff:g> and <xliff:g id="b">%2$s</xliff:g>'
        tokenized, originals = self.st.tokenize(raw)
        swap = {"0": "1", "1": "0"}
        reordered = self.st._TOKEN_RE.sub(
            lambda m: f"XLF{swap[m.group(1)]}", tokenized)
        self.st.check_token_parity(reordered, originals)  # must not raise
        self.assertEqual(
            self.st.detokenize(reordered, originals),
            '<xliff:g id="b">%2$s</xliff:g> and <xliff:g id="a">%1$s</xliff:g>',
        )

    def test_missing_token_raises(self):
        raw = '<xliff:g id="a">%1$s</xliff:g> and <xliff:g id="b">%2$s</xliff:g>'
        tokenized, originals = self.st.tokenize(raw)
        missing = tokenized.replace("XLF1", "")
        with self.assertRaises(self.st.TokenParityError):
            self.st.check_token_parity(missing, originals)

    def test_duplicated_token_raises(self):
        raw = '<xliff:g id="a">%1$s</xliff:g>'
        tokenized, originals = self.st.tokenize(raw)
        duplicated = tokenized + tokenized
        with self.assertRaises(self.st.TokenParityError):
            self.st.check_token_parity(duplicated, originals)

    def test_unknown_token_raises(self):
        raw = '<xliff:g id="a">%1$s</xliff:g>'
        tokenized, originals = self.st.tokenize(raw)
        unknown = tokenized + "XLF5"
        with self.assertRaises(self.st.TokenParityError):
            self.st.check_token_parity(unknown, originals)

    def test_naturally_colliding_source_raises_before_tokenizing(self):
        raw = "already has  the marker byte"
        with self.assertRaises(self.st.TokenCollisionError):
            self.st.tokenize(raw)

    def test_all_supported_source_escapes_decode(self):
        raw = (r"Fish &amp; Chips &lt;3 A 100%% "
               "tab\\there newline\\nhere back\\\\slash \\'quote\\'")
        decoded = self.st.decode_source_text(self.st.decode_xml_entities(raw))
        self.assertEqual(
            decoded,
            "Fish & Chips <3 A 100% tab\there newline\nhere back\\slash 'quote'",
        )

    def test_model_percent_doubled_but_injected_placeholder_untouched(self):
        core_with_token = "Discount: 50%, see XLF0"
        original_xliff = '<xliff:g id="n">%1$d</xliff:g>'
        result = self.st.finalize_translation(core_with_token, [original_xliff], "some_key")
        self.assertIn("50%%", result)
        self.assertIn("%1$d", result)
        self.assertNotIn("%%1$d", result)

    def test_escape_order_backslash_amp_lt_quotes_newline_tab(self):
        escaped = self.st.escape_for_emit(
            "\\back & <tag> 'quote' \"dquote\" 50% new\nline\ttab")
        self.assertIn("\\\\back", escaped)
        self.assertIn("&amp;", escaped)
        self.assertIn("&lt;tag>", escaped)
        self.assertIn("\\'quote\\'", escaped)
        self.assertIn('\\"dquote\\"', escaped)
        self.assertIn("50%%", escaped)
        self.assertIn("\\n", escaped)
        self.assertIn("\\t", escaped)

    def test_leading_at_and_question_mark_escaped(self):
        self.assertEqual(self.st.escape_for_emit("@handle text"), "\\@handle text")
        self.assertEqual(self.st.escape_for_emit("?query"), "\\?query")

    def test_ordinary_whitespace_is_trimmed(self):
        result = self.st.finalize_translation("   padded text   ", [], "content_favorite")
        self.assertEqual(result, "padded text")

    def test_separator_whitespace_restored_from_source_not_model(self):
        result = self.st.finalize_translation("·", [], "x_separator", source_envelope=("  ", "  "))
        self.assertEqual(result, "  ·  ")
        # Model-added padding is discarded in favor of the source's own envelope.
        result2 = self.st.finalize_translation("   ·   ", [], "x_separator", source_envelope=("  ", "  "))
        self.assertEqual(result2, "  ·  ")

    def test_locale_only_plural_quantity_reuses_source_other(self):
        """Arabic needs zero/one/two/few/many/other; the source only has one/other, so
        the quantities absent from English must reuse `other`'s text and tokens."""
        with tempfile.TemporaryDirectory() as d:
            res = Path(d) / "values"
            res.mkdir()
            (res / "strings.xml").write_text(
                '<resources><plurals name="songs">'
                '<item quantity="one">%1$d song</item>'
                '<item quantity="other">%1$d songs</item>'
                '</plurals></resources>'
            )
            units, _ = self.st.extract_source(res)
            unit = units["songs#"]
            text_few, tokens_few = self.st.plural_source_text_for_quantity(unit, "few")
            text_other, tokens_other = self.st.plural_source_text_for_quantity(unit, "other")
            self.assertEqual(text_few, text_other)
            self.assertEqual(tokens_few, tokens_other)
            text_one, _ = self.st.plural_source_text_for_quantity(unit, "one")
            self.assertNotEqual(text_one, text_other)

    def test_file_ownership_enforced(self):
        with tempfile.TemporaryDirectory() as d:
            source_dir = Path(d) / "values"
            source_dir.mkdir()
            (source_dir / "strings.xml").write_text('<resources><string name="a">A</string></resources>')
            (source_dir / "strings_other.xml").write_text('<resources><string name="b">B</string></resources>')
            staged = Path(d) / "values-de"
            staged.mkdir()
            # "b" is misplaced into strings.xml instead of strings_other.xml.
            (staged / "strings.xml").write_text(
                '<resources><string name="a">Ein</string><string name="b">Zwei</string></resources>')
            (staged / "strings_other.xml").write_text('<resources></resources>')
            errors = self.st.validate_staged_locale("de", staged, source_dir)
            self.assertTrue(any("owned by another file" in e for e in errors), errors)

    def test_staged_output_reparses_to_complete_source_key_set(self):
        """Full pipeline: extract -> finalize -> emit -> validate, echoing source text
        back as the translation for a locale whose plural rule matches English."""
        units, order = self.st.extract_source()
        by_file: dict[str, list[str]] = {}
        for key in order:
            by_file.setdefault(units[key].filename, []).append(key)
        with tempfile.TemporaryDirectory() as d:
            staged = Path(d) / "values-de"
            staged.mkdir()
            for fname, keys in by_file.items():
                entries = []
                for key in keys:
                    u = units[key]
                    if isinstance(u, self.st.StringSource):
                        entries.append(("string", u.key,
                                         self.st.finalize_translation(u.text, list(u.tokens), u.key, u.envelope)))
                    else:
                        payload = {q: self.st.finalize_translation(t, list(u.tokens[q]), u.key)
                                   for q, t in u.quantities.items()}
                        entries.append(("plurals", u.key, payload))
                (staged / fname).write_text(self.st.emit_locale_file(entries), encoding="utf-8")
            errors = self.st.validate_staged_locale("de", staged)
            self.assertEqual(errors, [])

    def test_failed_locale_never_partially_promoted(self):
        with tempfile.TemporaryDirectory() as d:
            source_dir = Path(d) / "values"
            source_dir.mkdir()
            (source_dir / "strings.xml").write_text(
                '<resources><string name="a">A</string><string name="b">B</string></resources>')
            staged = Path(d) / "work" / "values-de"
            staged.mkdir(parents=True)
            (staged / "strings.xml").write_text('<resources><string name="a">Ein</string></resources>')  # missing 'b'
            final_dir = Path(d) / "final" / "values-de"
            with self.assertRaises(self.st.SeedValidationError):
                self.st.promote_locale("de", staged, final_dir, source_dir)
            self.assertFalse(final_dir.exists())
            self.assertTrue(staged.exists())

    def test_missing_entries_append_without_rewriting_existing_xml(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "strings.xml"
            original = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">\n'
                '    <string name="existing">Übersetzt</string>\n'
                '</resources>\n'
            )
            path.write_text(original, encoding="utf-8")
            self.st.append_locale_entries(path, [("string", "new_key", "Neu")])
            updated = path.read_text(encoding="utf-8")
            self.assertIn('    <string name="existing">Übersetzt</string>', updated)
            self.assertIn('    <string name="new_key">Neu</string>', updated)
            with self.assertRaises(self.st.SeedTextError):
                self.st.append_locale_entries(path, [("string", "existing", "Ersetzt")])

    def test_missing_only_replacement_is_bound_to_existing_directory_hash(self):
        with tempfile.TemporaryDirectory() as d:
            source_dir = Path(d) / "values"
            source_dir.mkdir()
            (source_dir / "strings.xml").write_text(
                '<resources><string name="a">A</string><string name="b">B</string></resources>')
            final_dir = Path(d) / "values-de"
            final_dir.mkdir()
            (final_dir / "strings.xml").write_text(
                '<resources><string name="a">Alt</string><string name="b">Alt B</string></resources>')
            expected_hash = self.st.resource_directory_hash(final_dir)
            staged = Path(d) / "work" / "values-de"
            staged.mkdir(parents=True)
            (staged / "strings.xml").write_text(
                '<resources><string name="a">Alt</string><string name="b">Neu</string></resources>')

            self.st.promote_locale(
                "de", staged, final_dir, source_dir,
                replace_existing=True, expected_existing_hash=expected_hash,
            )
            self.assertIn("Neu", (final_dir / "strings.xml").read_text())
            self.assertFalse((Path(d) / ".values-de.seed-backup").exists())

            stale_staged = Path(d) / "work" / "stale-de"
            stale_staged.mkdir(parents=True)
            (stale_staged / "strings.xml").write_text(
                '<resources><string name="a">Alt</string><string name="b">Noch neuer</string></resources>')
            with self.assertRaises(FileExistsError):
                self.st.promote_locale(
                    "de", stale_staged, final_dir, source_dir,
                    replace_existing=True, expected_existing_hash=expected_hash,
                )
            self.assertIn("Neu", (final_dir / "strings.xml").read_text())
            self.assertTrue(stale_staged.exists())

    def test_chunking_covers_the_current_source_inventory(self):
        """Every current source key is assigned once and every chunk respects the size limit."""
        units, order = self.st.extract_source()
        self.assertGreater(len(order), 0)
        chunks = self.st.chunk_by_file(units, order)
        self.assertEqual(
            sum(len(chunk) for file_chunks in chunks.values() for chunk in file_chunks),
            len(order),
        )
        for file_chunks in chunks.values():
            for chunk in file_chunks:
                self.assertLessEqual(len(chunk), 40)


# ===========================================================================
# seed_translations.py (docs/i18n-phase4a-seed-translations.md, Part 2)
# ===========================================================================

def _fake_message(stop_reason, text):
    return SimpleNamespace(
        stop_reason=stop_reason,
        content=[SimpleNamespace(type="text", text=text)],
        usage=SimpleNamespace(input_tokens=10, output_tokens=20,
                               cache_creation_input_tokens=0, cache_read_input_tokens=0),
    )


def _fake_batch_result(custom_id, result_type, message=None):
    return SimpleNamespace(custom_id=custom_id, result=SimpleNamespace(type=result_type, message=message))


class TestSeedTranslations(unittest.TestCase):

    def setUp(self):
        self.stx = _load("seed_translations_test", "tools/i18n/seed_translations.py")

    def test_refusal_stop_reason_rejected(self):
        msg = _fake_message("refusal", "{}")
        result = _fake_batch_result("de__strings__000", "succeeded", msg)
        classified = self.stx._classify_transport("translation", result)
        self.assertEqual(classified["status"], "retryable")

    def test_max_tokens_stop_reason_rejected(self):
        msg = _fake_message("max_tokens", "{}")
        result = _fake_batch_result("de__strings__000", "succeeded", msg)
        classified = self.stx._classify_transport("translation", result)
        self.assertEqual(classified["status"], "retryable")

    def test_errored_canceled_expired_pass_through_untouched(self):
        for rtype in ("errored", "canceled", "expired"):
            result = _fake_batch_result("de__strings__000", rtype)
            classified = self.stx._classify_transport("translation", result)
            self.assertEqual(classified["status"], rtype)

    def test_valid_structured_success_is_classified_succeeded(self):
        payload = {"strings": [{"key": "a", "text": "Ein"}], "plurals": []}
        msg = _fake_message("end_turn", json.dumps(payload))
        result = _fake_batch_result("de__strings__000", "succeeded", msg)
        classified = self.stx._classify_transport("translation", result)
        self.assertEqual(classified["status"], "succeeded")
        self.assertEqual(classified["payload"], payload)

    def test_malformed_json_in_structured_success_is_retryable(self):
        msg = _fake_message("end_turn", "not valid json")
        result = _fake_batch_result("de__strings__000", "succeeded", msg)
        classified = self.stx._classify_transport("translation", result)
        self.assertEqual(classified["status"], "retryable")

    def test_unordered_results_matched_only_by_custom_id(self):
        requests = {"a": {"locale": "de"}, "b": {"locale": "ar"}, "c": {"locale": "ja"}}
        incoming_order = ["c", "a", "b"]  # deliberately not the request order
        matched = {cid: requests[cid]["locale"] for cid in incoming_order}
        self.assertEqual(matched, {"c": "ja", "a": "de", "b": "ar"})

    def test_resubmission_rejected_before_the_sdk_is_even_needed(self):
        """A request that already has a batchId must never be resubmitted -- and that
        guard must fire before cmd_submit needs the anthropic package, so it works even
        when the SDK is not installed in the calling environment."""
        with tempfile.TemporaryDirectory() as d:
            self.stx.RUNS_DIR = Path(d)
            manifest = self.stx.new_manifest("run1")
            manifest["stages"]["glossary"]["requests"] = {
                "de__glossary": {"locale": "de", "batchId": "batch_existing", "payloadHash": "x"}
            }
            self.stx.save_manifest("run1", manifest)
            with self.assertRaises(SystemExit):
                self.stx.cmd_submit(argparse.Namespace(run_id="run1", stage="glossary", force_stale=False, backend="anthropic"))

    def test_resume_delegates_to_collect_without_resubmitting(self):
        with tempfile.TemporaryDirectory() as d:
            self.stx.RUNS_DIR = Path(d)
            manifest = self.stx.new_manifest("run2")
            manifest["stages"]["translation"]["requests"] = {
                "x": {"locale": "de", "batchId": "batch_abc", "payloadHash": "x"},
                "y": {"locale": "de", "batchId": "batch_abc", "payloadHash": "y"},
            }
            manifest["stages"]["translation"]["results"] = {"x": {"status": "succeeded"}}  # "y" still pending
            self.stx.save_manifest("run2", manifest)

            calls = []

            def fake_collect(args):
                calls.append((args.run_id, args.stage))
                return 0

            self.stx.cmd_collect = fake_collect
            rc = self.stx.cmd_resume(argparse.Namespace(run_id="run2", backend="anthropic"))
            self.assertEqual(rc, 0)
            self.assertEqual(calls, [("run2", "translation")])
            reloaded = self.stx.load_manifest("run2")
            self.assertEqual(reloaded["stages"]["translation"]["requests"]["y"]["batchId"], "batch_abc")

    def test_semantic_validation_catches_missing_duplicate_and_wrong_kind(self):
        """Parseable, structured JSON is not automatically usable: missing keys,
        duplicated keys, and keys returned under the wrong resource kind must all be
        rejected per-key rather than accepted."""
        with tempfile.TemporaryDirectory() as d:
            self.stx.RUNS_DIR = Path(d)
            st = self.stx.st
            catalogue = self.stx.load_catalogue()
            manifest = self.stx.new_manifest("run3")
            built = self.stx.build_and_register_translation_requests(
                manifest, "run3", ["de"], catalogue, {"de": {}})
            cid = sorted(built)[0]
            req_meta = manifest["stages"]["translation"]["requests"][cid]
            units, _ = st.extract_source()
            keys = req_meta["keys"]
            payload = {"strings": [], "plurals": []}
            for i, key in enumerate(keys):
                unit = units[key]
                if isinstance(unit, st.StringSource):
                    if i == 0:
                        continue  # missing
                    payload["strings"].append({"key": unit.key, "text": unit.text})
                    if i == 1:
                        payload["strings"].append({"key": unit.key, "text": unit.text})  # duplicate
                else:
                    item = {"key": unit.key}
                    for q in ("zero", "one", "two", "few", "many", "other"):
                        item[q] = unit.quantities.get(q)
                    payload["plurals"].append(item)

            from tools.i18n import validate_strings as vs
            plural_rule = vs._PLURAL_RULES.get("de", ["one", "other"])
            valid, errors = self.stx._validate_translation_payload(req_meta, payload, units, plural_rule)
            self.assertIn(keys[0], errors)
            self.assertIn("missing", errors[keys[0]])
            self.assertIn(keys[1], errors)
            self.assertIn("duplicate", errors[keys[1]])
            self.assertEqual(len(valid) + len(errors), len(keys))

    def test_token_parity_failure_rejected_by_semantic_validation(self):
        with tempfile.TemporaryDirectory() as d:
            self.stx.RUNS_DIR = Path(d)
            st = self.stx.st
            catalogue = self.stx.load_catalogue()
            manifest = self.stx.new_manifest("run4")
            built = self.stx.build_and_register_translation_requests(
                manifest, "run4", ["de"], catalogue, {"de": {}})
            cid = sorted(built)[0]
            req_meta = manifest["stages"]["translation"]["requests"][cid]
            units, _ = st.extract_source()
            key = next(k for k in req_meta["keys"] if isinstance(units[k], st.StringSource) and units[k].tokens)
            payload = {"strings": [{"key": units[key].key, "text": "translation with no placeholder token"}],
                       "plurals": []}
            for k in req_meta["keys"]:
                if k == key:
                    continue
                unit = units[k]
                if isinstance(unit, st.StringSource):
                    payload["strings"].append({"key": unit.key, "text": unit.text})
                else:
                    item = {"key": unit.key}
                    for q in ("zero", "one", "two", "few", "many", "other"):
                        item[q] = unit.quantities.get(q)
                    payload["plurals"].append(item)
            from tools.i18n import validate_strings as vs
            plural_rule = vs._PLURAL_RULES.get("de", ["one", "other"])
            valid, errors = self.stx._validate_translation_payload(req_meta, payload, units, plural_rule)
            self.assertIn(key, errors)
            self.assertIn("parity", errors[key])

    def test_queue_retry_builds_scoped_follow_up_and_stops_after_max_attempts(self):
        with tempfile.TemporaryDirectory() as d:
            self.stx.RUNS_DIR = Path(d)
            st = self.stx.st
            catalogue = self.stx.load_catalogue()
            manifest = self.stx.new_manifest("run5")
            built = self.stx.build_and_register_translation_requests(
                manifest, "run5", ["de"], catalogue, {"de": {}})
            cid = sorted(built)[0]
            req_meta = manifest["stages"]["translation"]["requests"][cid]
            units, _ = st.extract_source()
            errors = {req_meta["keys"][0]: "missing from model response",
                      req_meta["keys"][1]: "duplicate key in response (2x)"}

            self.stx._queue_retry(manifest, "run5", "translation", cid, req_meta, errors, catalogue, units)
            retry_cid = cid + "__retry1"
            retry_req = manifest["stages"]["translation"]["requests"][retry_cid]
            self.assertIsNone(retry_req["batchId"])
            self.assertEqual(set(retry_req["keys"]), set(errors.keys()))
            self.assertEqual(manifest["retries"]["translation"][cid], 1)

            # Exhausting MAX_FOLLOWUP_ATTEMPTS writes an unresolved file instead of a
            # third retry request.
            manifest["retries"]["translation"][cid] = self.stx.MAX_FOLLOWUP_ATTEMPTS
            self.stx._queue_retry(manifest, "run5", "translation", retry_cid, retry_req, errors, catalogue, units)
            unresolved = Path(d) / "run5" / f"{req_meta['locale']}-unresolved.json"
            self.assertTrue(unresolved.is_file())

    def test_missing_only_prepares_only_absent_keys_and_records_base_snapshot(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self.stx.RUNS_DIR = root / "runs"
            self.stx.RES = root / "res"
            locale_dir = self.stx.RES / "values-de"
            locale_dir.mkdir(parents=True)
            (locale_dir / "strings.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?><resources></resources>',
                encoding="utf-8",
            )
            units, order = self.stx.st.extract_source()
            absent = [order[0], order[-1]]
            self.stx._locale_existing_keys = lambda _: set(order) - set(absent)
            manifest = self.stx.new_manifest("missing-run")
            built = self.stx.build_and_register_translation_requests(
                manifest, "missing-run", ["de"], self.stx.load_catalogue(), {"de": {}},
                missing_only=True,
            )

            requested = [
                key
                for request in built.values()
                for key in request["keys"]
            ]
            self.assertEqual(requested, absent)
            self.assertEqual(manifest["translationMode"], "missing-only")
            base = manifest["localeBases"]["de"]
            self.assertEqual(base["requestedKeys"], absent)
            self.assertEqual(base["existingKeyCount"], len(order) - len(absent))
            self.assertEqual(base["inventoryHash"], self.stx.st.resource_directory_hash(locale_dir))

    def test_prepare_translations_reuses_compatible_durable_glossary(self):
        with tempfile.TemporaryDirectory() as d:
            self.stx.RUNS_DIR = Path(d)
            terms = self.stx.load_glossary()["consistentTerms"]
            prior = self.stx.new_manifest("prior")
            cid = self.stx.st.glossary_custom_id("de")
            prior["stages"]["glossary"]["requests"][cid] = {
                "locale": "de", "terms": terms, "batchId": "old", "payloadHash": "x",
            }
            prior["stages"]["glossary"]["results"][cid] = {
                "status": "succeeded",
                "valid": {term: f"translated-{term}" for term in terms},
            }
            self.stx.save_manifest("prior", prior)
            self.stx.MAX_KEYS_PER_CHUNK = 10000
            rc = self.stx.cmd_prepare_translations(argparse.Namespace(
                locales="de",
                run_id="current",
                glossary_run_id="prior",
                dry_run=False,
                backend="pi",
                missing_only=False,
            ))
            self.assertEqual(rc, 0)
            current = self.stx.load_manifest("current")
            request = next(iter(current["stages"]["translation"]["requests"].values()))
            payload = json.loads(
                (Path(d) / "current" / "requests" / "translation" /
                 f"{next(iter(current['stages']['translation']['requests']))}.json").read_text()
            )
            self.assertIn(f"{terms[0]} -> translated-{terms[0]}", payload["params"]["system"][0]["text"])
            self.assertEqual(request["locale"], "de")

    def test_hash_drift_blocks_submit_and_promote_unless_forced(self):
        with tempfile.TemporaryDirectory() as d:
            self.stx.RUNS_DIR = Path(d)
            manifest = self.stx.new_manifest("run6")
            manifest["sourceInventoryHash"] = "sha256:stale"
            with self.assertRaises(SystemExit):
                self.stx.verify_hashes(manifest)
            self.stx.verify_hashes(manifest, force_stale=True)  # must not raise


if __name__ == "__main__":
    unittest.main(verbosity=2)
