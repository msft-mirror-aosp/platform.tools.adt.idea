"""Failing K2 ART tests."""

load(":ij-debugger-tests.bzl", "include_k2")

# List of all failing tests excluding InlineScopesAndK2IdeK2CodeEvaluateExpressionTestGenerated and
# K2IndyLambdaKotlinSteppingTestGenerated which are just duplicates of
# K2IdeK2CodeKotlinEvaluateExpressionTestGenerated and K2IdeK2CodeKotlinSteppingTestGenerated.
_tests = [
    "K2IdeK2CodeBreakpointHighlightingTestGenerated.testAnonymousFun",  # b/575539285, b/578038532
    "K2IdeK2CodeBreakpointHighlightingTestGenerated.testConditionalReturn",  # b/575823083
    "K2IdeK2CodeBreakpointHighlightingTestGenerated.testConditionalReturnInLambda",  # b/575823083
    "K2IdeK2CodeBreakpointHighlightingTestGenerated.testDefaultLambdaParameter",  # b/578038532
    "K2IdeK2CodeBreakpointHighlightingTestGenerated.testMultiLineLambda",  # b/578123886
    "K2IdeK2CodeContinuationStackTraceTestGenerated.testSuspendFun1",  # b/579461799
    "K2IdeK2CodeContinuationStackTraceTestGenerated.testSuspendFunStackTraceFolded",  # b/579461799
    "K2IdeK2CodeContinuationStackTraceTestGenerated.testSuspendFunStackTraceFoldedIDEA345945",  # b/579461799
    "K2IdeK2CodeContinuationStackTraceTestGenerated.testSuspendFunStackTraceHidden",  # b/579461799
    "K2IdeK2CodeContinuationStackTraceTestGenerated.testSuspendFunWithInner",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Todo$Skipped_suspend_frame.testSuspendFramesBeforeLaunch",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testChannel",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testFramesAboveCoroutineOwner",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testNestedLaunches",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testNestedScopes1",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testNestedScopesWithSyncFrame",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testNestedWithContexts1",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testNestedWithContexts2",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testSuspendFramesBeforeWithContext",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testTwoWithContextBlocks",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testWithContextDispatched1",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testWithContextDispatched2",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testWithContextUndispatched1",  # b/579461799
    "K2IdeK2CodeCoroutineAsyncStackTraceTestGenerated$Uncategorized.testWithTimeout",  # b/579461799
    "K2IdeK2CodeCoroutineDumpTestGenerated.testTwoDumps",  # b/579461799
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$MultipleBreakpoints$ReifiedParamEvaluation.testMultilineCall",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$MultipleBreakpoints$ReifiedParamEvaluation.testReifiedParamEvalWithComplexInvocationLookup",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$MultipleBreakpoints$Uncategorized.testInlineStackTraceWithTryFinally",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Frame.testFrameObject",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Frame.testIdea352803",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Lambdas.testLambdaOnSecondLine",  # b/578038532
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Lambdas.testOneLineFunctionalExpression",  # b/578038532
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Lambdas.testOneLineLambda",  # b/578038532
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Lambdas.testTwoLambdasOnOneLineFirst",  # b/578038532
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testCheckCastOtherClassloader",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testColor",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testInlineWithAnonymousObject",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testLambdaToString",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testOnObjectHeader",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testSmartStepIntoLambdaWithParameterDestructuring",  # b/578038532
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testSmartStepIntoSamWithParameterDestructuring",  # b/578038532
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testStepIntoInlineLambdaWithParameterDestructuring2",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testStepIntoInlineSamWithParameterDestructuring",  # b/578038532
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testStepIntoLambdaWithParameterDestructuring",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Uncategorized.testStepIntoSamWithParameterDestructuring",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testBreakpointInOneLineLambda",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testCoroutineUnitElimination",  # b/578123886
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testEquals",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testFilterFunctionCallsInTryCatchBlock",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testFinallyBlock",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testFunLiteral",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testFunctionCallStoredToVariable",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testIdea334404",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testIdea362864",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testInitBlocks",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testInsideCallChain",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testLambdaWithImplicitReturn",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testLineBreakpointWithMethodReference",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSequentialInlineFunctionCalls",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSeveralFunLiterals",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSeveralFunLiteralsInClass",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoAsyncLambda",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoDeferredLambdas",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoDeferredSamLambdas",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoInterfaceImpl",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoJavaLambda",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoLabeledLambda",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoLambdaWithDefaultArgsFun",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoLambdaWithparametersDestructuring",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoLambdasOnManyLines",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoLambdasOnOneLine",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoMethodWithJvmNameAnnotation",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoMultiplyInlinedLambdas",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoOnLambdaBreakpoint",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoSamLambdaFromJavaFunInterface",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoSamLambdaFromKotlinFunInterface",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoStoredLambda",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoSuspendLambda",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoTargetsOrder",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoToLambdaParameter",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepIntoToLambdaParameterAfterSam",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSmartStepWithInlineClass",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testStepOverInvokeMethodInLambda",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testStepThroughDefaultArgsConstructor",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Custom.testSyntheticProvider",
    "K2IdeK2CodeKotlinSteppingTestGenerated$Filters.testStepIntoSpecificKotlinClasses",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepInto.testClassObjectFunFromTopLevel",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepInto.testObjectFun",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepIntoOnly.testInterfacesJvmDefaultDisable",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepIntoOnly.testLocalDelegatedPropertyAndClinit",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepIntoOnly.testSyntheticMethods",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepIntoOnly.testSyntheticMethodsSkip",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepOut.testStepOutConstructorWithValueClassParam",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepOver$Coroutines.testSequenceNested2",  # b/578038532
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepOver$Uncategorized.testKt15652",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepOver$Uncategorized.testStepOverConstructorWithValueClassParam",
    "K2IdeK2CodeKotlinSteppingTestGenerated$StepOver$Uncategorized.testStopInNamelessFunInInlineCall",
    "K2IdeK2CodeKotlinVariablePrintingTestGenerated.testOptimisedVariablesInSuspendContexts",
    "K2IdeK2CodeKotlinVariablePrintingTestGenerated.testOptimisedVariablesInSuspendInline",
    "K2IdeK2CodeKotlinVariablePrintingTestGenerated.testOptimisedVariablesWithLambdas",
    "K2IdeK2CodeKotlinVariablePrintingTestGenerated.testOptimisedVariablesWithWhen",
    "K2IdeK2CodeKotlinVariablePrintingTestGenerated.testScopeVariablesInInlineSuspendBlock",
    "K2IdeK2CodeKotlinVariablePrintingTestGenerated.testSpilledCapturedVariables",
    "K2IdeK2CodeXCoroutinesStackTraceTestGenerated.testCoroutineSuspendFun",
    "K2IdeK2CodeXCoroutinesStackTraceTestGenerated.testCoroutineSuspendFun136",
    "K2IdeK2CodeXCoroutinesStackTraceTestGenerated.testSuspendMain",
    "K2IdeK2CoroutineViewJobHierarchyTestGenerated.testCoroutinesHierarchy2",
    "K2IdeK2CoroutineViewJobHierarchyTestGenerated.testCoroutinesHierarchy3",
    "K2IdeK2CoroutineViewJobHierarchyTestGenerated.testOneCoroutine",
]
ART_K2_FAILING = [include_k2(test) for test in _tests]

_tests_with_order_related_failure = [
    "K2IdeK2CodeKotlinVariablePrintingTestGenerated.testOptimisedVariablesInSuspendContexts",
]
TESTS_WITH_ORDER_RELATED_FAILURE = [include_k2(test) for test in _tests_with_order_related_failure]

_tests_with_errors = [
    "InlineScopesAndK2IdeK2CodeEvaluateExpressionTestGenerated$SingleBreakpoint$Frame.testEvaluatableGetters",
    "K2FlowAsyncStackTraceTestGenerated.testSharedFlowComplex",
    "K2FlowAsyncStackTraceTestGenerated.testSharedFlowDebounce",
    "K2FlowAsyncStackTraceTestGenerated.testSharedFlowMerge",
    "K2FlowAsyncStackTraceTestGenerated.testSharedFlowMultipleFlowsSameValues",
    "K2FlowAsyncStackTraceTestGenerated.testSharedFlowNulls",
    "K2FlowAsyncStackTraceTestGenerated.testSharedFlowSimple",
    "K2FlowAsyncStackTraceTestGenerated.testStateFlowComplex",
    "K2FlowAsyncStackTraceTestGenerated.testStateFlowMerge",
    "K2FlowAsyncStackTraceTestGenerated.testStateFlowMultipleFlowsSameValues",
    "K2FlowAsyncStackTraceTestGenerated.testStateFlowNulls",
    "K2FlowAsyncStackTraceTestGenerated.testStateFlowSimple",
    "K2IdeK2CodeKotlinEvaluateExpressionTestGenerated$SingleBreakpoint$Frame.testEvaluatableGetters",
]
TESTS_WITH_ERRORS = [include_k2(test) for test in _tests_with_errors]
