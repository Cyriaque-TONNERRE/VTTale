plugins {
    id("java")
}

dependencies {
    implementation(project(":api"))

    // Test-only: the gamesystem tests wire the module onto a real kernel.
    // This edge intentionally does NOT exist in the production dependency graph.
    testImplementation(project(":kernel"))
}
