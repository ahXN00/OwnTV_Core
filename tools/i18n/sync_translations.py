#!/usr/bin/env python3
"""Two-way sync between `main` and Weblate's branch, `translations`, one string at a time.

Weblate works only on `translations` and pushes there directly; it holds every language, including
those that do not ship yet. Nothing else writes that branch except this script, which runs while
Weblate is locked (.github/workflows/translations.yml), so Weblate never has a commit to replay and
cannot hit a merge conflict. Git's line merge is never used in either direction.

    sync_translations.py to-main        # translations -> working tree of main (shipped languages)
    sync_translations.py to-weblate     # prints the next `translations` commit, or nothing
    sync_translations.py ready          # unshipped languages at the readiness threshold
    sync_translations.py promote <id>   # copy that language from `translations`, mark it shipped

`to-main`: for every packaged language, each string Weblate changed since the last sync replaces
ours; everything else stays as main has it, so strings we added, reworded or deleted are kept.
Where both sides changed one string the translator's text wins.

`to-weblate`: builds the next `translations` commit without touching the working tree: main's tree
(English, code, every shipped language - which after `to-main` already contains Weblate's work),
plus the unshipped languages exactly as Weblate has them. Its parents are the current
`translations` (so Weblate fast-forwards) and main (so the next sync's base is this point).

Run `to-main` after `git fetch origin translations`, from a checkout of main.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES_REL = 'core/src/main/res'
RES = ROOT / RES_REL
I18N = ROOT / 'tools' / 'i18n'
TRANSLATIONS = os.environ.get('TRANSLATIONS_REF', 'origin/translations')
MAIN = os.environ.get('MAIN_REF', 'HEAD')
# A top-level <string> or <plurals>, with the indentation in front of it.
ELEMENT = re.compile(r'[ \t]*<(string|plurals)\b[^>]*\bname="([^"]+)"[^>]*>.*?</\1>', re.S)

sys.path.insert(0, str(I18N))
import validate_strings  # noqa: E402  (coverage uses the validator's own parser)
import check_weblate_format  # noqa: E402


def git(*args: str, env: dict | None = None) -> str:
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True, text=True,
                          encoding='utf-8', env=env).stdout.strip()


def show(rev: str, path: str) -> str:
    r = subprocess.run(['git', 'show', f'{rev}:{path}'], cwd=ROOT, capture_output=True)
    return r.stdout.decode('utf-8') if r.returncode == 0 else ''


def elements(text: str) -> dict[str, str]:
    return {m.group(2): m.group(0) for m in ELEMENT.finditer(text)}


def catalogue() -> list[dict]:
    return json.loads((I18N / 'locales.json').read_text(encoding='utf-8'))


def shipped_dirs() -> set[str]:
    return {l['resourceDirectory'] for l in catalogue()
            if l.get('packaged') is True and l['resourceDirectory'] != 'values'}


def language_dirs(rev: str) -> list[str]:
    return [n.split('/')[-1] for n in git('ls-tree', '--name-only', f'{rev}:{RES_REL}').split()
            if n.startswith('values-')]


def merge_strings(base: str, theirs: str, ours: str, english_order: list[str]) -> str:
    """`ours`, with every string `theirs` changed since `base` taken from `theirs`."""
    b, t, o = elements(base), elements(theirs), elements(ours)
    result = ours
    for name, tel in t.items():
        if b.get(name) == tel or o.get(name) == tel:
            continue  # unchanged on their side, or already equal here
        if name in o:
            result = result.replace(o[name], tel, 1)
        elif name in english_order:  # translated a string that still exists in English
            idx = english_order.index(name)
            anchor = next((o[k] for k in reversed(english_order[:idx]) if k in o), None)
            if anchor:
                result = result.replace(anchor, anchor + '\n' + tel, 1)
            else:
                result = result.replace('</resources>', tel + '\n</resources>', 1)
            o[name] = tel
    for name, bel in b.items():  # they removed a string we still hold unchanged
        if name not in t and o.get(name) == bel:
            result = re.sub(r'\r?\n' + re.escape(bel), '', result, count=1)
    return result


def cmd_to_main() -> int:
    base = git('merge-base', MAIN, TRANSLATIONS)
    changed = set()
    for d in sorted(shipped_dirs()):
        for path in sorted((RES / d).glob('strings*.xml')):
            rel = f'{RES_REL}/{d}/{path.name}'
            theirs = show(TRANSLATIONS, rel)
            if not theirs:
                continue
            ours = path.read_text(encoding='utf-8')
            order = list(elements((RES / 'values' / path.name).read_text(encoding='utf-8')))
            merged = merge_strings(show(base, rel), theirs, ours, order)
            if merged != ours:
                path.write_text(merged, encoding='utf-8', newline='')
                changed.add(d)
    check_weblate_format.fix()
    print('to-main: ' + (', '.join(sorted(changed)) if changed else 'nothing new'))
    return 0


def cmd_to_weblate() -> int:
    """Print the next `translations` commit; print nothing when its tree would not change."""
    shipped = shipped_dirs()
    index = (ROOT / git('rev-parse', '--git-dir')) / 'sync-translations.index'
    env = {**os.environ, 'GIT_INDEX_FILE': str(index)}
    try:
        git('read-tree', MAIN, env=env)
        entries = []
        for d in language_dirs(TRANSLATIONS):
            if d in shipped:
                continue
            # An unshipped language as Weblate has it. main never carries one (validate_strings).
            entries.append(git('ls-tree', '-r', TRANSLATIONS, '--', f'{RES_REL}/{d}'))
        if entries:
            subprocess.run(['git', 'update-index', '--index-info'], cwd=ROOT, env=env, check=True,
                           input=('\n'.join(e for e in entries if e) + '\n').encode('utf-8'))
        tree = git('write-tree', env=env)
    finally:
        index.unlink(missing_ok=True)
    if tree == git('rev-parse', f'{TRANSLATIONS}^{{tree}}'):
        return 0
    print(git('commit-tree', tree, '-p', TRANSLATIONS, '-p', MAIN,
              '-m', 'Sync from main\n\nEnglish source and shipped languages from main; '
                    'unshipped languages kept as Weblate has them.'))
    return 0


def coverage(rev: str, d: str) -> float:
    import tempfile
    src, _ = validate_strings._parse_dir(RES / 'values')
    keys = [k for k, v in src.items() if v.get('translatable', True)]
    with tempfile.TemporaryDirectory() as tmp:
        for name in git('ls-tree', '--name-only', f'{rev}:{RES_REL}/{d}').split():
            (Path(tmp) / name.split('/')[-1]).write_text(show(rev, f'{RES_REL}/{d}/{name.split("/")[-1]}'),
                                                         encoding='utf-8')
        loc, _ = validate_strings._parse_dir(Path(tmp))
    return round(100 * sum(1 for k in keys if k in loc) / len(keys), 1) if keys else 0.0


def cmd_ready() -> int:
    """One line per unshipped language at the threshold: `<id or dir> <percent> <name>`."""
    threshold = json.loads((I18N / 'community.json').read_text(encoding='utf-8'))[
        'translationReadinessThresholdPercent']
    by_dir = {l['resourceDirectory']: l for l in catalogue()}
    shipped = shipped_dirs()
    for d in language_dirs(TRANSLATIONS):
        if d in shipped:
            continue
        pct = coverage(TRANSLATIONS, d)
        if pct >= threshold:
            e = by_dir.get(d)
            print(f"{e['id'] if e else d} {pct} {e['englishName'] if e else 'NOT-IN-CATALOGUE'}")
    return 0


def cmd_promote(lang: str) -> int:
    cat = catalogue()
    entry = next(l for l in cat if l['id'] == lang)
    d = entry['resourceDirectory']
    (RES / d).mkdir(parents=True, exist_ok=True)
    for name in git('ls-tree', '--name-only', f'{TRANSLATIONS}:{RES_REL}/{d}').split():
        name = name.split('/')[-1]
        (RES / d / name).write_text(show(TRANSLATIONS, f'{RES_REL}/{d}/{name}'), encoding='utf-8', newline='')
    entry.update(tier=1, packaged=True, pickerVisible=True)
    (I18N / 'locales.json').write_text(json.dumps(cat, indent=2, ensure_ascii=False) + '\n',
                                       encoding='utf-8', newline='')
    check_weblate_format.fix()
    print(f'promoted {lang} ({d})')
    return 0


if __name__ == '__main__':
    args = sys.argv[1:]
    if args[:1] == ['to-main']:
        sys.exit(cmd_to_main())
    if args[:1] == ['to-weblate']:
        sys.exit(cmd_to_weblate())
    if args[:1] == ['ready']:
        sys.exit(cmd_ready())
    if args[:1] == ['promote'] and len(args) == 2:
        sys.exit(cmd_promote(args[1]))
    print(__doc__)
    sys.exit(2)
