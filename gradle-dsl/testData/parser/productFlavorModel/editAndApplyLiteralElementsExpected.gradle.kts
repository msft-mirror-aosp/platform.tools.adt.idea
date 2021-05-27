android {
  defaultConfig {
    setApplicationId("com.example.myapplication-1")
    setDimension("efgh")
    maxSdkVersion(24)
    minSdkPreview = "16"
    setMultiDexEnabled(false)
    targetSdkPreview = "23"
    setTestApplicationId("com.example.myapplication-1.test")
    setTestFunctionalTest(true)
    setTestHandleProfiling(false)
    testInstrumentationRunner("efgh")
    useJack(true)
    setVersionCode("2")
    setVersionName("2.0")
  }
}
