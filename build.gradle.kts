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
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("issue.cli.MainKt")
    applicationDistribution.from("src/main/scripts") {
        into("bin")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
