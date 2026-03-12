plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("cn.varsa:cli-core:0.1.0-SNAPSHOT")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("issue.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
