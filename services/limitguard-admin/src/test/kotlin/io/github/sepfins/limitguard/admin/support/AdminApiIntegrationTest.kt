package io.github.sepfins.limitguard.admin.support

import io.github.sepfins.limitguard.api.Limitguard
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.Properties
import java.util.UUID
import javax.sql.DataSource

// The Admin HTTP API over a real core: every test gets its own freshly migrated schema.
@SpringBootTest(properties = ["spring.flyway.enabled=false"])
@AutoConfigureMockMvc
@Import(AdminApiIntegrationTest.TestBeans::class)
abstract class AdminApiIntegrationTest {
    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var limitguard: Limitguard

    @Autowired
    private lateinit var dataSource: DelegatingDataSource

    @BeforeEach
    fun useFreshSchema() {
        dataSource.targetDataSource = MigratedSchemas.fresh()
    }

    protected fun createdId(result: ResultActionsDsl): UUID {
        val response = result.andExpect { status { isCreated() } }.andReturn().response
        return UUID.fromString(response.getHeader("Location")!!.substringAfterLast('/'))
    }

    protected fun createTenant(name: String): UUID = createdId(
        mockMvc.post("/limitguard/admin/api/v1/tenants") {
            with(platformAdmin())
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
        },
    )

    @TestConfiguration(proxyBeanMethods = false)
    class TestBeans {
        @Bean
        fun dataSource(): DelegatingDataSource = DelegatingDataSource(MigratedSchemas.fresh())

        @Bean
        fun clientRegistrationRepository(): ClientRegistrationRepository =
            mock(ClientRegistrationRepository::class.java)
    }
}

// One container per test JVM.
private object MigratedSchemas {
    private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

    fun fresh(): DataSource {
        val schema = "test_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/limitguard/migration").load().migrate()
        return DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
        }
    }
}
