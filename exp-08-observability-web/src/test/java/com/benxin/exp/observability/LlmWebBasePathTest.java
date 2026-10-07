package com.benxin.exp.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code llm.web.base-path} 的自定义：把端点从 {@code /llm} 挪到 {@code /api/llm}。
 *
 * <p>单独一个类、单独一份 properties，因此它会拿到<b>自己的 ApplicationContext</b>
 * （Spring 的上下文缓存按配置区分）—— 这正是"改一个配置项就换一套端点路径"的证明。</p>
 *
 * <p>注意 {@code @RequestMapping("${llm.web.base-path:/llm}")} 是<b>占位符</b>：
 * base-path 在启动期就必须确定，运行期改不了。想让同一个进程同时暴露两套路径，
 * 得自己再写一个 Controller。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "llm.web.enabled=true",
        "llm.web.base-path=/api/llm"
})
@DisplayName("示例 08 · 自定义 llm.web.base-path")
class LlmWebBasePathTest extends ObservabilityTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("base-path 改成 /api/llm 后，新路径可用、旧路径 404")
    void basePathIsConfigurable() {
        ResponseEntity<List> moved = rest.getForEntity(
                "http://localhost:" + port + "/api/llm/agents", List.class);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody()).isNotEmpty();

        ResponseEntity<String> old = rest.getForEntity(
                "http://localhost:" + port + "/llm/agents", String.class);
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("base-path 变了之后，chat 端点也跟着走（控制器上的占位符作用于整个类）")
    void chatEndpointMovesToo() {
        var response = rest.postForEntity("http://localhost:" + port + "/api/llm/agents/chatAssistant/chat",
                java.util.Map.of("message", "自我介绍"), java.util.Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(response.getBody().get("agent"))).isEqualTo("chatAssistant");
    }
}
