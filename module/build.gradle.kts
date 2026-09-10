plugins {
    id("java")
}

dependencies {
    implementation(project(":api"))

    // Test-only: the integration tests wire real modules onto a real kernel.
    // This edge intentionally does NOT exist in the production dependency graph.
    testImplementation(project(":kernel"))
}
