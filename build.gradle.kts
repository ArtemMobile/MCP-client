plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}


dependencies {
    implementation(dependencies.platform(libs.ktor.bom))
    implementation(libs.mcp.kotlin.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.slf4j.simple)

    testImplementation(libs.mcp.kotlin.client)
    testImplementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))

    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.7")

    implementation("io.ktor:ktor-client-java:3.2.3")


}


tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.example.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // To add all of the dependencies
    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    // Optional: If you need to include all dependencies in the JAR (e.g., for a "fat jar")
    // from(configurations.runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) })
}