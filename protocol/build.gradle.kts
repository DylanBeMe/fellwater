plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

java {
    withSourcesJar()
}
