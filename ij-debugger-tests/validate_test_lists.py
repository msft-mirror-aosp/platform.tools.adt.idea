#!/usr/bin/env python3
"""Validate that tests in *-expected-results.txt and *-failing-tests.bzl exist.

Can be run in two ways:
1. As a `bazel test` target (default mode): asserts all tests listed in
   `*-expected-results.txt` and `*-failing-tests.bzl` exist in the compiled
   test jars from tools/idea.
2. With `--update`: removes tests that no longer exist from those files:
     tools/base/bazel/bazel run //tools/adt/idea/ij-debugger-tests:validate-test-lists -- --update
"""

from __future__ import annotations

import argparse
import ast
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


DEFAULT_BASE_DIR = Path(__file__).resolve().parent
_INCLUDE_FN_PREFIXES = {
    "include_k2": "org.jetbrains.kotlin.idea.k2.debugger.test.cases.",
    "include_compose": "org.jetbrains.kotlin.idea.compose.k2.debugger.test.cases.",
}
TEST_CMD = "tools/base/bazel/bazel test --config=remote //tools/adt/idea/ij-debugger-tests:validate-test-lists"
UPDATE_CMD = (
    "tools/base/bazel/bazel run //tools/adt/idea/ij-debugger-tests:validate-test-lists"
    " -- --update"
)

_JAVAP_CLASS_RE = re.compile(
    r"\b(?:class|interface|enum|record)\s+([^\s<{]+)"
    r"(?:<[^>]*>)?"
    r"(?:\s+extends\s+([^\s<{]+))?"
)
_JAVAP_METHOD_RE = re.compile(r"([A-Za-z0-9_$]+)\s*\(")


def _resolve_runfile(path_str: str) -> Path:
    """Resolve a Bazel $(rootpaths) or $(JAVABASE) path in runfiles."""
    candidate = Path(path_str)
    if candidate.is_file():
        return candidate
    if path_str.startswith("external/"):
        rel = Path("..") / path_str.removeprefix("external/")
        if rel.is_file():
            return rel
    return candidate


def _resolve_javap() -> str:
    """Resolve the path to the javap executable."""
    javap_env = os.environ.get("JAVAP")
    if javap_env:
        candidate = _resolve_runfile(javap_env)
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("javap")
    if found:
        return found
    raise FileNotFoundError(f"javap executable not found (JAVAP={javap_env!r})")


def _resolve_test_jars() -> list[Path]:
    """Resolve compiled test .jar paths from the TEST_JARS environment variable."""
    raw = os.environ.get("TEST_JARS", "")
    jars: list[Path] = []
    for token in raw.split():
        path = _resolve_runfile(token)
        if path.suffix != ".jar":
            continue
        if not path.is_file():
            raise FileNotFoundError(f"Test jar not found: {token}")
        jars.append(path)
    return jars


def index_methods_from_jars(jar_paths: list[Path], javap_bin: str) -> set[str]:
    """Index all `fqcn.methodName` entries across the given test jars using javap."""
    if not jar_paths:
        return set()

    class_names: list[str] = []
    for jar_path in jar_paths:
        with zipfile.ZipFile(jar_path, "r") as zf:
            for name in zf.namelist():
                if name.endswith(".class") and name != "module-info.class":
                    class_names.append(name[:-6].replace("/", "."))

    declared: dict[str, set[str]] = {}
    super_map: dict[str, str | None] = {}
    classpath = os.pathsep.join(str(p) for p in jar_paths)

    # Batch class names to stay well within command-line length limits.
    batch_size = 200
    for i in range(0, len(class_names), batch_size):
        batch = class_names[i : i + batch_size]
        proc = subprocess.run(
            [javap_bin, "-p", "-cp", classpath, *batch],
            capture_output=True,
            text=True,
            check=True,
        )
        current_class: str | None = None
        for line in proc.stdout.splitlines():
            if not line.startswith(" "):
                m = _JAVAP_CLASS_RE.search(line)
                if m and line.rstrip().endswith("{"):
                    current_class = m.group(1)
                    declared.setdefault(current_class, set())
                    super_map[current_class] = m.group(2)
                elif line.strip() == "}":
                    current_class = None
            elif current_class is not None and "(" in line:
                m = _JAVAP_METHOD_RE.search(line)
                if m:
                    declared[current_class].add(m.group(1))

    # Resolve methods inherited from superclasses present in the same jars.
    all_methods: set[str] = set()
    for fqcn in declared:
        cur: str | None = fqcn
        visited: set[str] = set()
        while cur and cur not in visited and cur in declared:
            visited.add(cur)
            for method in declared[cur]:
                all_methods.add(f"{fqcn}.{method}")
            cur = super_map.get(cur)

    return all_methods


def check_expected_results_file(
    path: Path, existing_methods: set[str], update: bool, dry_run: bool
) -> list[str]:
    """Check (and optionally prune) a `*-expected-results.txt` file."""
    lines = path.read_text(encoding="utf-8").splitlines()
    new_lines: list[str] = []
    missing: list[str] = []

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            new_lines.append(line)
            continue
        if stripped not in existing_methods:
            missing.append(stripped)
            if not update:
                new_lines.append(line)
        else:
            new_lines.append(line)

    if missing and update and not dry_run:
        path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")

    return missing


def _collect_test_list_prefixes(tree: ast.Module) -> dict[str, str]:
    """Map list variable names to package prefixes based on `[include_*(t) for t in <var>]`."""
    list_prefixes: dict[str, str] = {}
    for node in tree.body:
        if not (isinstance(node, ast.Assign) and isinstance(node.value, ast.ListComp)):
            continue
        elt = node.value.elt
        if not (isinstance(elt, ast.Call) and isinstance(elt.func, ast.Name)):
            continue
        prefix = _INCLUDE_FN_PREFIXES.get(elt.func.id)
        if prefix is None:
            continue
        for gen in node.value.generators:
            if isinstance(gen.iter, ast.Name):
                list_prefixes[gen.iter.id] = prefix
    return list_prefixes


def check_failing_tests_bzl_file(
    path: Path, existing_methods: set[str], update: bool, dry_run: bool
) -> list[str]:
    """Check (and optionally prune) a `*-failing-tests.bzl` file."""
    content = path.read_text(encoding="utf-8")
    tree = ast.parse(content, filename=str(path))
    test_list_prefixes = _collect_test_list_prefixes(tree)

    missing_entries: list[str] = []
    lines_to_remove: set[int] = set()
    # Map 1-based lineno -> (col_offset, end_col_offset, replacement) for single-line lists.
    inline_replacements: dict[int, tuple[int, int, str]] = {}

    for node in tree.body:
        if not (isinstance(node, ast.Assign) and isinstance(node.value, ast.List)):
            continue
        target_names = [t.id for t in node.targets if isinstance(t, ast.Name)]
        package_prefix = next(
            (test_list_prefixes[name] for name in target_names if name in test_list_prefixes),
            None,
        )
        if package_prefix is None:
            continue
        list_node = node.value
        missing_elts: set[int] = set()
        for idx, elt in enumerate(list_node.elts):
            if isinstance(elt, ast.Constant) and isinstance(elt.value, str):
                entry = elt.value
                fq = entry if entry.startswith("org.") else package_prefix + entry
                if fq not in existing_methods:
                    missing_entries.append(entry)
                    missing_elts.add(idx)

        if not missing_elts:
            continue

        if list_node.lineno == list_node.end_lineno:
            kept = [
                json.dumps(elt.value)
                for idx, elt in enumerate(list_node.elts)
                if idx not in missing_elts and isinstance(elt, ast.Constant)
            ]
            inline_replacements[list_node.lineno] = (
                list_node.col_offset,
                list_node.end_col_offset or list_node.col_offset,
                "[" + ", ".join(kept) + "]",
            )
        else:
            for idx in missing_elts:
                elt = list_node.elts[idx]
                lines_to_remove.add(elt.lineno)

    if (lines_to_remove or inline_replacements) and update and not dry_run:
        lines = content.splitlines()
        new_lines: list[str] = []
        for lineno, line in enumerate(lines, start=1):
            if lineno in lines_to_remove:
                continue
            if lineno in inline_replacements:
                start_col, end_col, repl = inline_replacements[lineno]
                line = line[:start_col] + repl + line[end_col:]
            new_lines.append(line)
        path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")

    return missing_entries


def resolve_base_dir(update: bool) -> Path:
    """Resolve the ij-debugger-tests directory containing the config files."""
    workspace_dir = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    if update and workspace_dir:
        return Path(workspace_dir) / "tools/adt/idea/ij-debugger-tests"
    return DEFAULT_BASE_DIR


def write_junit_xml(
    xml_path: Path, results: list[tuple[str, list[str]]]
) -> None:
    """Write JUnit XML to XML_OUTPUT_FILE so Fusion displays failures in the Tests tab."""
    failures = sum(1 for _, missing in results if missing)
    testsuites = ET.Element("testsuites")
    testsuite = ET.SubElement(
        testsuites,
        "testsuite",
        name="validate-test-lists",
        tests=str(len(results)),
        failures=str(failures),
    )
    for file_name, missing in results:
        testcase = ET.SubElement(
            testsuite,
            "testcase",
            classname="validate_test_lists",
            name=file_name,
        )
        if missing:
            summary = f"Missing {len(missing)} test(s) in {file_name}"
            details = "\n".join(
                [f"{summary}:"]
                + [f"  - {test}" for test in missing]
                + ["", "Run with --update to remove them:", f"  {UPDATE_CMD}"]
            )
            failure = ET.SubElement(testcase, "failure", message=summary)
            failure.text = details

    xml_path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(testsuites).write(xml_path, encoding="utf-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Validate that all tests in *-expected-results.txt and "
            "*-failing-tests.bzl exist in tools/idea."
        )
    )
    parser.add_argument(
        "--update",
        action="store_true",
        help="Remove tests that no longer exist from the config files.",
    )
    parser.add_argument(
        "--dry-run",
        "-n",
        action="store_true",
        help="With --update, show what would be removed without modifying files.",
    )
    args = parser.parse_args()

    base_dir = resolve_base_dir(update=args.update)
    existing_methods = index_methods_from_jars(_resolve_test_jars(), _resolve_javap())

    if not existing_methods:
        print(
            "Error: No test methods were indexed from TEST_JARS.\n"
            f"Run via Bazel:\n  {TEST_CMD}\n  {UPDATE_CMD}",
            file=sys.stderr,
        )
        return 1

    expected_results_files = sorted(base_dir.glob("*-expected-results.txt"))
    failing_bzl_files = sorted(base_dir.glob("*-failing-tests.bzl"))

    if not expected_results_files and not failing_bzl_files:
        print(f"Error: No config files found in {base_dir}", file=sys.stderr)
        return 1

    total_missing = 0
    file_results: list[tuple[str, list[str]]] = []

    for txt_file in expected_results_files:
        missing = check_expected_results_file(
            txt_file, existing_methods, update=args.update, dry_run=args.dry_run
        )
        file_results.append((txt_file.name, missing))
        if missing:
            total_missing += len(missing)
            if args.update:
                verb = "[DRY-RUN] Would remove" if args.dry_run else "Removed"
                print(f"{verb} {len(missing)} missing test(s) from {txt_file.name}:")
            else:
                print(f"Missing {len(missing)} test(s) in {txt_file.name}:", file=sys.stderr)
            out = sys.stdout if args.update else sys.stderr
            for test in missing:
                print(f"  - {test}", file=out)

    for bzl_file in failing_bzl_files:
        missing = check_failing_tests_bzl_file(
            bzl_file, existing_methods, update=args.update, dry_run=args.dry_run
        )
        file_results.append((bzl_file.name, missing))
        if missing:
            total_missing += len(missing)
            if args.update:
                verb = "[DRY-RUN] Would remove" if args.dry_run else "Removed"
                print(f"{verb} {len(missing)} missing test(s) from {bzl_file.name}:")
            else:
                print(f"Missing {len(missing)} test(s) in {bzl_file.name}:", file=sys.stderr)
            out = sys.stdout if args.update else sys.stderr
            for test in missing:
                print(f"  - {test}", file=out)

    xml_output_file = os.environ.get("XML_OUTPUT_FILE")
    if xml_output_file and not args.update:
        write_junit_xml(Path(xml_output_file), file_results)

    if total_missing == 0:
        print("All tests in *-expected-results.txt and *-failing-tests.bzl exist.")
        return 0

    if args.update:
        return 0

    print(
        f"\nFound {total_missing} test reference(s) that no longer exist in tools/idea.\n"
        f"Run with --update to remove them:\n  {UPDATE_CMD}",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
