android {
  namespace = "com.example.app"
  customOption("opt")
}
customTask("verify") {
  group = "verification"
}
assignedTask = createTask("lint")
