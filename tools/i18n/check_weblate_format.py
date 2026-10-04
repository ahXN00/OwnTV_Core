#!/usr/bin/env python3
"""Keep every translation file byte-for-byte in the form Weblate writes.

Weblate saves Android strings through translate-toolkit, and a save rewrites the
whole file into that library's own form. Any translation file we write
differently is rewritten on Weblate's next save, and those rewrites are what made
Weblate's repository impossible to merge (2026-10-04). Saving every file the way
Weblate would means a Weblate commit only ever touches the lines a translator
actually changed.

    python tools/i18n/check_weblate_format.py check   # CI: fail on any file Weblate would rewrite
    python tools/i18n/check_weblate_format.py fix     # rewrite those files in Weblate's form

Run `fix` after any scripted insertion into the translation files, then the other
gates. Needs translate-toolkit: pip install -r tools/i18n/requirements-weblate.txt

What Weblate's form cannot hold, so never write it into a translation:
non-breaking spaces (any spelling), a space before a placeholder at the start of
a string, and plural forms Android only uses for fractions (cs `many`,
pl/ru `other` are rewritten from the integer forms).
"""
import difflib
import sys
from pathlib import Path

from translate.storage.aresource import AndroidResourceFile

RES = Path(__file__).resolve().parents[2] / 'core' / 'src' / 'main' / 'res'


def weblate_form(path: Path) -> bytes:
    """The file as Weblate would write it after a translator saves every unit."""
    store = AndroidResourceFile()
    # Without the language, plural forms English lacks are dropped on save.
    store.settargetlanguage(path.parent.name[len('values-'):].replace('-r', '_'))
    store.parse(path.read_bytes())
    for unit in store.units:
        unit.target = unit.target
    return bytes(store)


def lines(data: bytes) -> list[str]:
    return data.decode('utf-8').replace('\r\n', '\n').splitlines()


def drifted_files() -> list[tuple[Path, bytes]]:
    """Every translation file not already in Weblate's form, with that form."""
    out = []
    for path in sorted(RES.glob('values-*/strings*.xml')):
        expected = weblate_form(path)
        if lines(path.read_bytes()) != lines(expected):
            out.append((path, expected))
    return out


def fix() -> int:
    drifted = drifted_files()
    for path, expected in drifted:
        path.write_bytes(expected)
    return len(drifted)


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else 'check'
    if mode not in ('check', 'fix'):
        print(__doc__)
        return 2
    if mode == 'fix':
        print(f'Weblate format: rewrote {fix()} file(s)')
        return 0
    drifted = drifted_files()
    for path, expected in drifted:
        diff = difflib.unified_diff(lines(path.read_bytes()), lines(expected), lineterm='', n=0)
        print(f'{path.relative_to(RES.parents[3])}:')
        for line in list(diff)[2:14]:
            print('   ', line[:160])
    if drifted:
        print(f'\n{len(drifted)} translation file(s) are not in Weblate\'s form; Weblate would rewrite them on its next '
              'save and conflict with main. Run: python tools/i18n/check_weblate_format.py fix')
        return 1
    print('Weblate format OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
