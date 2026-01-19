plugins {
    kotlin("jvm") version "1.9.24"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.yaml:snakeyaml:2.2")
}

application {
    mainClass.set("issue.cli.MainKt")
}

kotlin {
    jvmToolchain(17)
}
