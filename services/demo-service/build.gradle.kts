import com.github.gradle.node.npm.task.NpmTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "io.github.sepfins"
version = "0.0.1-SNAPSHOT"
description = "LimitGuard customer banking demo"

extra["jooq.version"] = "3.21.5"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation("io.github.sepfins:limitguard-core:0.0.1-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

node {
    download.set(true)
    version.set("24.15.0")
    nodeProjectDir.set(file("ui"))
}

val npmCi by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmSetup)
    workingDir.set(file("ui"))
    args.set(listOf("ci"))
    inputs.files("ui/package.json", "ui/package-lock.json")
    outputs.dir("ui/node_modules")
}

val uiTest by tasks.registering(NpmTask::class) {
    dependsOn(npmCi)
    workingDir.set(file("ui"))
    args.set(listOf("run", "test", "--", "--watch=false"))
    inputs.dir("ui/src")
    inputs.files("ui/angular.json", "ui/tsconfig.json", "ui/tsconfig.spec.json")
}

val uiBuild by tasks.registering(NpmTask::class) {
    dependsOn(npmCi)
    workingDir.set(file("ui"))
    args.set(listOf("run", "build:all"))
    inputs.dir("ui/src")
    inputs.files("ui/angular.json", "ui/tsconfig.json", "ui/tsconfig.app.json", "ui/tsconfig.payment.app.json")
    outputs.dir("ui/dist")
}

val generatedWebResources = layout.buildDirectory.dir("generated-resources/main")

val syncDemoUi by tasks.registering(Sync::class) {
    dependsOn(uiBuild)
    from("ui/dist/limitguard-demo-ui/browser")
    into(generatedWebResources.map { it.dir("static/limitguard/demo") })
}

val syncPaymentUi by tasks.registering(Sync::class) {
    dependsOn(uiBuild)
    from("ui/dist/limitguard-payment-ui/browser")
    into(generatedWebResources.map { it.dir("static/limitguard/payments") })
}

sourceSets {
    main {
        resources.srcDir(generatedWebResources)
    }
}

tasks.processResources {
    dependsOn(syncDemoUi, syncPaymentUi)
}

tasks.test {
    dependsOn(uiTest)
    useJUnitPlatform()
}
