package io.github.sepfins.limitguard.admin.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AdminSpaController {
    @GetMapping("/")
    fun home(): String = "redirect:/limitguard/admin/"

    @GetMapping(
        "/limitguard/admin",
        "/limitguard/admin/",
        "/limitguard/admin/tenants",
        "/limitguard/admin/tenants/{tenantId}",
        "/limitguard/admin/tenants/{tenantId}/{area:products|channels|operations|configurations|policies}",
        "/limitguard/admin/tenants/{tenantId}/policies/new",
    )
    fun adminApplication(): String = "forward:/limitguard/admin/index.html"
}
