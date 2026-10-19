#!/usr/bin/env python3
"""Script to remove passed tests from ij-debugger-tests expected failure configurations.

Reads test.xml files from a given directory, finds tests that failed with
'Expected to fail but passed', prints them out, and removes them from:
1. The corresponding art-*-expected-results.txt file.
2. tools/adt/idea/ij-debugger-tests/art-k2-failing-tests.bzl (if listed there).
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


DEFAULT_BASE_DIR = Path(__file__).resolve().parent
DEFAULT_BZL_FILE = DEFAULT_BASE_DIR / "art-k2-failing-tests.bzl"
EXPECTED_FAIL_REASON = "Expected to fail but passed"
PACKAGE_PREFIX = "org.jetbrains.kotlin.idea.k2.debugger.test.cases."


def is_expected_fail_but_passed(testcase_elem: ET.Element) -> bool:
    """Check if testcase failed because it was expected to fail but passed."""
    for child in testcase_elem:
        if child.tag in ("failure", "error"):
            msg = child.attrib.get("message", "")
            if EXPECTED_FAIL_REASON in msg:
                return True
            text = child.text or ""
            if EXPECTED_FAIL_REASON in text:
                return True
    return False


def find_passed_tests(testlogs_dir: Path) -> set[str]:
    """Find all tests that failed with 'Expected to fail but passed' in test.xml files."""
    passed_tests: set[str] = set()
    xml_files = list(testlogs_dir.rglob("test.xml")) if testlogs_dir.is_dir() else ([testlogs_dir] if testlogs_dir.is_file() else [])

    if not xml_files:
        print(f"Warning: No test.xml files found in {testlogs_dir}", file=sys.stderr)
        return passed_tests

    for xml_path in xml_files:
        try:
            tree = ET.parse(xml_path)
            for tc in tree.iter("testcase"):
                if is_expected_fail_but_passed(tc):
                    classname = tc.attrib.get("classname", "")
                    name = tc.attrib.get("name", "")
                    if classname and name:
                        passed_tests.add(f"{classname}.{name}")
        except Exception as e:
            print(f"Warning: Failed to parse XML file {xml_path}: {e}", file=sys.stderr)

    return passed_tests


def resolve_config_files(testlogs_dir: Path, base_dir: Path, explicit_config: Path | None) -> list[Path]:
    """Determine which *-expected-results.txt file(s) correspond to the test run."""
    if explicit_config:
        if not explicit_config.is_file():
            print(f"Error: Specified config file does not exist: {explicit_config}", file=sys.stderr)
            sys.exit(1)
        return [explicit_config]

    # Try matching directory path component ending in '-tests'
    for part in testlogs_dir.parts:
        if part.endswith("-tests"):
            target_prefix = part[:-len("-tests")]
            candidate = base_dir / f"{target_prefix}-expected-results.txt"
            if candidate.is_file():
                return [candidate]

    # Try matching any expected-results prefix in the path
    for cand in base_dir.glob("*-expected-results.txt"):
        prefix = cand.name.replace("-expected-results.txt", "")
        if prefix in str(testlogs_dir):
            return [cand]

    # Fallback: check all art-*-expected-results.txt in base_dir
    return sorted(base_dir.glob("art-*-expected-results.txt"))


def remove_tests_from_expected_results(
    config_file: Path, tests_to_remove: set[str], dry_run: bool = False
) -> list[str]:
    """Remove matching test lines from expected-results.txt file."""
    lines = config_file.read_text(encoding="utf-8").splitlines()
    new_lines: list[str] = []
    removed: list[str] = []

    for line in lines:
        stripped = line.strip()
        if stripped in tests_to_remove:
            removed.append(stripped)
        else:
            new_lines.append(line)

    if removed and not dry_run:
        config_file.write_text("\n".join(new_lines) + "\n", encoding="utf-8")

    return removed


def remove_tests_from_bzl(
    bzl_file: Path, tests_to_remove: set[str], dry_run: bool = False
) -> list[str]:
    """Remove matching test entries from art-k2-failing-tests.bzl file."""
    if not bzl_file.is_file():
        return []

    lines = bzl_file.read_text(encoding="utf-8").splitlines()
    new_lines: list[str] = []
    removed: list[str] = []

    for line in lines:
        stripped = line.strip()
        # Skip comment lines and docstrings
        if stripped.startswith("#") or stripped.startswith(('"""', "'''")):
            new_lines.append(line)
            continue

        # Look for string literals representing test names
        m = re.search(r'["\']([^"\']+)["\']', stripped)
        if m and (stripped.startswith('"') or stripped.startswith("'")):
            entry = m.group(1)
            is_match = False
            # Check full name match, or package-prefixed match, or endswith match for class.method
            if entry in tests_to_remove or f"{PACKAGE_PREFIX}{entry}" in tests_to_remove:
                is_match = True
            elif "." in entry and any(t.endswith(f".{entry}") for t in tests_to_remove):
                is_match = True

            if is_match:
                removed.append(entry)
                continue

        new_lines.append(line)

    if removed and not dry_run:
        bzl_file.write_text("\n".join(new_lines) + "\n", encoding="utf-8")

    return removed


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Find tests that passed unexpectedly and remove them from config files."
    )
    parser.add_argument(
        "testlogs_dir",
        type=Path,
        help="Path to directory containing test.xml files (e.g. bazel testlogs dir)",
    )
    parser.add_argument(
        "--config-file",
        "-c",
        type=Path,
        default=None,
        help="Path to expected results file (default: auto-detected from testlogs_dir or art-*-expected-results.txt)",
    )
    parser.add_argument(
        "--bzl-file",
        "-b",
        type=Path,
        default=DEFAULT_BZL_FILE,
        help=f"Path to art-k2-failing-tests.bzl (default: {DEFAULT_BZL_FILE})",
    )
    parser.add_argument(
        "--dry-run",
        "-n",
        action="store_true",
        help="Show what would be removed without making changes to files.",
    )

    args = parser.parse_args()

    testlogs_dir = args.testlogs_dir.resolve()
    if not testlogs_dir.exists():
        print(f"Error: Directory or file does not exist: {testlogs_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Searching for test.xml files in: {testlogs_dir}...")
    passed_tests = find_passed_tests(testlogs_dir)

    if not passed_tests:
        print("No tests found with reason 'Expected to fail but passed'.")
        return

    print(f"\nFound {len(passed_tests)} test(s) that failed with 'Expected to fail but passed':")
    for test in sorted(passed_tests):
        print(test)

    # 1. Remove from corresponding expected-results config file(s)
    config_files = resolve_config_files(testlogs_dir, DEFAULT_BASE_DIR, args.config_file)
    print("\nUpdating expected results config files...")
    total_removed_configs = 0
    for cfg in config_files:
        removed_from_cfg = remove_tests_from_expected_results(
            cfg, passed_tests, dry_run=args.dry_run
        )
        if removed_from_cfg:
            prefix = "[DRY-RUN] Would remove" if args.dry_run else "Removed"
            print(f"{prefix} {len(removed_from_cfg)} test(s) from {cfg.name}:")
            for t in removed_from_cfg:
                print(f"  - {t}")
            total_removed_configs += len(removed_from_cfg)
        else:
            print(f"No matching tests found in {cfg.name}.")

    # 2. Remove from art-k2-failing-tests.bzl
    bzl_file = args.bzl_file.resolve()
    print(f"\nUpdating {bzl_file.name}...")
    removed_from_bzl = remove_tests_from_bzl(bzl_file, passed_tests, dry_run=args.dry_run)
    if removed_from_bzl:
        prefix = "[DRY-RUN] Would remove" if args.dry_run else "Removed"
        print(f"{prefix} {len(removed_from_bzl)} test(s) from {bzl_file.name}:")
        for t in removed_from_bzl:
            print(f"  - {t}")
    else:
        print(f"No matching tests found in {bzl_file.name}.")

    print("\nDone.")


if __name__ == "__main__":
    main()
