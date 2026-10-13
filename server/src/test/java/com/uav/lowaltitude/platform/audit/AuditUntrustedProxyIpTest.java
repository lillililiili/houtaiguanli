package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

@SpringBootTest(classes = AuditProxyIpTest.Probe.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1")
class AuditUntrustedProxyIpTest {
    @Autowired TestRestTemplate http;
    @Test void untrustedPeerCannotSpoofAddressThroughForwardingHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "203.0.113.66");
        headers.set("X-Real-IP", "203.0.113.67");
        headers.set("Forwarded", "for=203.0.113.68");
        assertThat(http.exchange("/address", HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .isIn("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    }
}
