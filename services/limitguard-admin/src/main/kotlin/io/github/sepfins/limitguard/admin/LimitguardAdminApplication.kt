package io.github.sepfins.limitguard.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LimitguardAdminApplication

fun main(args: Array<String>) {
    runApplication<LimitguardAdminApplication>(*args)
}
