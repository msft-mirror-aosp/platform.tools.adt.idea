val mP by extra(mapOf("a" to "b", "c" to "d"))

android {
  compileSdkVersion(30)
  defaultConfig {
    manifestPlaceholders += mP
    targetSdkVersion(30)
  }
}
