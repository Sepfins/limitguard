plugins {
    `java-library`
    kotlin("jvm") version "2.4.0"
    id("nu.studer.jooq") version "10.2.1"
}

group = "io.github.sepfins"
version = "0.0.1-SNAPSHOT"
description = "limitguard-core"

val jooqVersion = "3.21.5"

fun optionalEnv(
    envName: String,
    envAliases: List<String> = emptyList(),
    defaultValue: String? = null,
): String? =
    sequenceOf(envName)
        .plus(envAliases)
        .firstNotNullOfOrNull { providers.environmentVariable(it).orNull }
        ?: defaultValue

val jooqJdbcDriver = optionalEnv(
    envName = "JOOQ_JDBC_DRIVER",
    defaultValue = "org.postgresql.Driver",
)!!

val jooqDbHost = optionalEnv(
    envName = "JOOQ_DB_HOST",
    defaultValue = "localhost",
)!!

val jooqDbPort = optionalEnv(
    envName = "JOOQ_DB_PORT",
    defaultValue = "5432",
)!!

val jooqDbName = optionalEnv(
    envName = "JOOQ_DB_NAME",
    envAliases = listOf("POSTGRES_DB"),
    defaultValue = "limitguard",
)!!

val jooqJdbcUrl = optionalEnv(
    envName = "JOOQ_JDBC_URL",
) ?: "jdbc:postgresql://$jooqDbHost:$jooqDbPort/$jooqDbName"

val jooqJdbcUser = optionalEnv(
    envName = "JOOQ_JDBC_USER",
    envAliases = listOf("POSTGRES_USER"),
    defaultValue = "limitguard",
)!!

val jooqJdbcPassword = optionalEnv(
    envName = "JOOQ_JDBC_PASSWORD",
    envAliases = listOf("POSTGRES_PASSWORD"),
    defaultValue = "limitguard"
)!!

val jooqInputSchema = optionalEnv(
    envName = "JOOQ_INPUT_SCHEMA",
    defaultValue = "public",
)!!

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("com.apicatalog:titanium-jcs:3.0.0-M3")
    implementation("io.github.jamsesso:json-logic-java:1.1.0")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
    jooqGenerator("org.postgresql:postgresql:42.7.8")
}

jooq {
    version.set(jooqVersion)
    edition.set(nu.studer.gradle.jooq.JooqEdition.OSS)

    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(false)

            jooqConfiguration.apply {
                jdbc.apply {
                    driver = jooqJdbcDriver
                    url = jooqJdbcUrl
                    user = jooqJdbcUser
                    password = jooqJdbcPassword
                }

                generator.apply {
                    name = "org.jooq.codegen.DefaultGenerator"

                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = jooqInputSchema
                        includes = ".*"
                        excludes = "flyway_schema_history"
                    }

                    generate.apply {
                        isRecords = true
                        isPojos = false
                        isDaos = false
                        isFluentSetters = true
                        isJavaTimeTypes = true
                    }

                    target.apply {
                        packageName = "io.github.sepfins.limitguard.infrastructure.jooq.generated"
                        directory = "build/generated-src/jooq/main"
                    }
                }
            }
        }
    }
}

sourceSets {
    main {
        java {
            srcDir("build/generated-src/jooq/main")
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
