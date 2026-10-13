package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exercises the real embedded Tomcat valve; MockMvc does not run container forwarding logic. */
@SpringBootTest(classes = AuditProxyIpTest.Probe.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditProxyIpTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
            SecurityAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class})
    @Import(AddressController.class)
    static class Probe { }

    @RestController static class AddressController {
        @GetMapping("/address") String address(HttpServletRequest request) { return request.getRemoteAddr(); }
    }
    @Autowired TestRestTemplate http;

    @Test void localProxySuppliesIpv4AndIpv6AndStopsAtFirstUntrustedHop() {
        assertThat(address("192.0.2.71")).isEqualTo("192.0.2.71");
        assertThat(address("2001:db8::71")).isEqualTo("2001:db8::71");
        assertThat(address("203.0.113.99, 192.0.2.71")).isEqualTo("192.0.2.71");
    }
    @Test void directRequestsKeepActualLoopbackAddress() {
        assertThat(http.getForObject("/address", String.class)).isIn("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    }
    private String address(String chain) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", chain);
        headers.set("X-Real-IP", "203.0.113.100");
        headers.set("Forwarded", "for=203.0.113.101");
        return http.exchange("/address", HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }
}
