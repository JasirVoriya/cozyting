plugins {
    id("java-library")
}

if (project.hasProperty("buildDir")) {
    layout.buildDirectory.set(file("${project.property("buildDir")}/hiddenapi-stubs"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
