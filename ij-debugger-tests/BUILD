load("@rules_python//python:defs.bzl", "py_test")
load(":art-k2-failing-tests.bzl", "ART_K2_FAILING", "TESTS_WITH_ERRORS", "TESTS_WITH_ORDER_RELATED_FAILURE")
load(":ij-debugger-tests.bzl", "debugger_test", "include_k2")

smoke_test = include_k2("K2PositionManagerTestGenerated$SingleFile.testClass")

debugger_test(
    name = "jvm-smoke-test",
    filters = [smoke_test],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
)

debugger_test(
    name = "art-smoke-test",
    filters = [smoke_test],
    run_on_art = True,
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
)

test_suite(
    name = "smoke-test",
    tests = [
        ":art-smoke-test",
        ":jvm-smoke-test",
    ],
)

debugger_test(
    name = "jvm-k2-tests",
    expected_results = "jvm-k2-expected-results.txt",
    shard_count = 50,
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
)

debugger_test(
    name = "art-k2-tests",
    expected_results = "art-k2-expected-results.txt",
    filters = [
        # Coroutines tests are too flaky and sometimes deadlock and fail the entire shard
        "exclude-classname=.*InlineScopesAndK2IdeK2CodeEvaluateExpressionTestGenerated.*\\$Coroutines\\$.*",
        "exclude-classname=.*K2IdeK2CodeKotlinEvaluateExpressionTestGenerated.*\\$Coroutines\\$.*",
        # The following classes contain tests that do not extend KotlinDescriptorTestCase and do not launch an actual VM so they are not relevant to ART
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.K2DfaAssistTest",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.K2FileApplicabilityCheckerTest",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.cases.K2BreakpointApplicabilityTestGenerated",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.cases.K2ClassNameCalculatorTestGenerated",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.cases.K2IdeK2CodeFileRankingTestGenerated",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.cases.K2KotlinExceptionFilterTestGenerated",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.cases.K2PositionManagerTestGenerated\\$.*",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.cases.K2SelectExpressionForDebuggerTestGenerated\\$.*",
        "exclude-classname=org.jetbrains.kotlin.idea.k2.debugger.test.cases.K2SmartStepIntoTestGenerated",
        # The following tests do not apply to ART
        "exclude-methodname=.*\\#testCheckCastOtherClassloader",  # Classloader hierarchy doesn't apply to ART
        "exclude-methodname=.*\\#testColor",  # Swing code
        "exclude-methodname=.*\\#testLambdaToString",  # Incompatible due to desugaring
    ],
    run_on_art = True,
    shard_count = 50,
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
)

# A target of just the failing tests
debugger_test(
    name = "art-k2-failing-tests",
    filters = ART_K2_FAILING,
    run_on_art = True,
    shard_count = 50,
    tags = ["manual"],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
)

debugger_test(
    name = "art-tests-with-order-related-failures",
    filters = TESTS_WITH_ORDER_RELATED_FAILURE,
    run_on_art = True,
    tags = ["manual"],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
)

debugger_test(
    name = "art-tests-with-errors",
    filters = TESTS_WITH_ERRORS,
    run_on_art = True,
    tags = ["manual"],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
)

debugger_test(
    name = "jvm-compose-tests",
    expected_results = "jvm-compose-expected-results.txt",
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/compose:compose_test_lib",
)

debugger_test(
    name = "art-compose-tests",
    expected_results = "art-compose-expected-results.txt",
    run_on_art = True,
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/compose:compose_test_lib",
)

debugger_test(
    name = "jvm-parcelize-tests",
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/parcelize:parcelize_test_lib",
)

debugger_test(
    name = "art-parcelize-tests",
    run_on_art = True,
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/parcelize:parcelize_test_lib",
)

debugger_test(
    name = "jvm-performance-tests",
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/performance:kotlin-jvm-debugger-performance-test_test_lib",
)

debugger_test(
    name = "art-performance-tests",
    run_on_art = True,
    tags = [
        "ci:studio-linux_very_flaky",
        "noci:studio-linux",
    ],
    test_dep = "@tools_idea//plugins/kotlin/jvm-debugger/test/performance:kotlin-jvm-debugger-performance-test_test_lib",
)

test_suite(
    name = "jvm",
    tags = ["manual"],
    tests = [
        ":jvm-compose-tests",
        ":jvm-k2-tests",
        ":jvm-parcelize-tests",
        ":jvm-performance-tests",
    ],
)

test_suite(
    name = "art",
    tags = ["manual"],
    tests = [
        ":art-compose-tests",
        ":art-k2-tests",
        ":art-parcelize-tests",
        ":art-performance-tests",
    ],
)

test_suite(
    name = "ij-debugger-tests",
    tags = ["manual"],
    tests = [
        ":art",
        ":jvm",
    ],
)

_TEST_LIST_DEPS = [
    "@tools_idea//plugins/kotlin/jvm-debugger/test/compose:compose_test_lib",
    "@tools_idea//plugins/kotlin/jvm-debugger/test/k2:kotlin-jvm-debugger-test_test_lib",
    "@tools_idea//plugins/kotlin/jvm-debugger/test/parcelize:parcelize_test_lib",
    "@tools_idea//plugins/kotlin/jvm-debugger/test/performance:kotlin-jvm-debugger-performance-test_test_lib",
]

py_test(
    name = "validate-test-lists",
    size = "small",
    srcs = ["validate_test_lists.py"],
    data = glob([
        "*-expected-results.txt",
        "*-failing-tests.bzl",
    ]) + [
        "@bazel_tools//tools/jdk:current_java_runtime",
    ] + _TEST_LIST_DEPS,
    env = {
        "JAVAP": "$(JAVABASE)/bin/javap",
        "TEST_JARS": " ".join(["$(rootpaths %s)" % dep for dep in _TEST_LIST_DEPS]),
    },
    main = "validate_test_lists.py",
    target_compatible_with = select({
        "@platforms//os:windows": ["@platforms//:incompatible"],
        "@platforms//os:macos": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
    toolchains = ["@bazel_tools//tools/jdk:current_java_runtime"],
)
