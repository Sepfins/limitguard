package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.infrastructure.jooq.JooqLimitguardFactory
import io.github.sepfins.limitguard.infrastructure.policy.jsonlogic.JsonLogicPolicyEngine
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.time.ClockProvider
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import javax.sql.DataSource

// One container per test JVM; every database gets its own freshly migrated schema.
object PostgresFacade {
    private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

    fun database(): DataSource {
        val schema = "test_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/limitguard/migration").load().migrate()
        return PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            currentSchema = schema
        }
    }

    fun create(
        clock: ClockProvider,
        engines: Collection<PolicyEngine> = listOf(JsonLogicPolicyEngine()),
        source: DataSource = database(),
        relayLimitChangeEvents: Boolean = false,
    ): Limitguard = JooqLimitguardFactory(source).create(
        clockProvider = clock, policyEngines = engines, relayLimitChangeEvents = relayLimitChangeEvents,
    )
}

fun DataSource.execute(sql: String, vararg values: Any?): Int = connection.use { connection ->
    connection.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }
}
