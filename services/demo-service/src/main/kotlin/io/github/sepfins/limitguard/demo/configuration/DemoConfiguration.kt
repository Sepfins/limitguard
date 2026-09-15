package io.github.sepfins.limitguard.demo.configuration

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.infrastructure.jooq.JooqLimitguardFactory
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import javax.sql.DataSource

@ConfigurationProperties("limitguard.demo")
data class DemoProperties(
    val scenarioKey: String = "default-household",
    val eventHeartbeat: Duration = Duration.ofSeconds(20),
)

@Configuration(proxyBeanMethods = false)
class DemoConfiguration {
    @Bean
    fun limitguard(dataSource: DataSource): Limitguard =
        JooqLimitguardFactory(dataSource).create()

    @Bean
    fun jdbcClient(dataSource: DataSource): JdbcClient = JdbcClient.create(dataSource)

    @Bean
    fun flywayMigrationStrategy(dataSource: DataSource): FlywayMigrationStrategy =
        FlywayMigrationStrategy { coreFlyway ->
            coreFlyway.migrate()
            Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(DEMO_SCHEMA)
                .schemas(DEMO_SCHEMA)
                .table("flyway_demo_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .locations("classpath:db/demo/migration")
                .load()
                .migrate()
        }

    private companion object {
        const val DEMO_SCHEMA = "limitguard_demo"
    }
}
