package io.github.everwynn.nova.ai.demo237;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2.3.7 只认 spring.factories：端点能返回 200，说明自动配置确实被加载了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndpointSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @SuppressWarnings("rawtypes")
    void onlineEndpointIsRegistered() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/ai/chat/online", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo(0);
    }
}
