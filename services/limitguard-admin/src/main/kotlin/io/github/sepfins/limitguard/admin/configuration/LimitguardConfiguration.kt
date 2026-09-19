package io.github.sepfins.limitguard.admin.configuration

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.infrastructure.jooq.JooqLimitguardFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class LimitguardConfiguration {
    @Bean
    fun limitguard(dataSource: DataSource): Limitguard =
        JooqLimitguardFactory(dataSource).create()
}
