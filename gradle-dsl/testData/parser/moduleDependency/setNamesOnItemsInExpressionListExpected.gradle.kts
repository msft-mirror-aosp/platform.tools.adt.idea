dependencies {
  compile(project(mapOf("configuration" to "flavor1Release", "path" to ":newName")))
  compile(project(":newName2"))
}