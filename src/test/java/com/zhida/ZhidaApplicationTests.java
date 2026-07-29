package com.zhida;

import com.zhida.config.ChatProperties;
import com.zhida.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "zhida.data-dir=target/test-data/application")
class ZhidaApplicationTests {
	@Autowired
	private ChatProperties chatProperties;
	@Autowired
	private ChatService chatService;

	@Test
	void contextLoads() {
	}

	@Test
	void providerNamesLoadAsUtf8() {
		assertEquals("DeepSeek（深度求索）", chatProperties.getProviders().get("deepseek").getName());
		assertEquals("GLM（智谱清言）", chatProperties.getProviders().get("glm").getName());
		assertEquals("deepseek", chatProperties.getDefaultProvider());
		assertEquals("glm-4-flash", chatProperties.getProviders().get("glm").getModel());
	}

	@Test
	void deepSeekIsTheDefaultProvider() {
		assertEquals("deepseek", chatService.providers().get(0).id());
	}

	@Test
	void messageManagementAssetsArePackagedTogether() throws IOException {
		String html = readClasspathResource("/static/index.html");
		String script = readClasspathResource("/static/app.js");
		String styles = readClasspathResource("/static/app.css");

		assertTrue(html.contains("id=\"message-search\""));
		assertTrue(html.contains("id=\"message-stats\""));
		assertTrue(html.contains("id=\"clear-messages\""));
		assertTrue(script.contains("function saveEditedMessage"));
		assertTrue(script.contains("function deleteMessage"));
		assertTrue(script.contains("function normalizeConversations"));
		assertTrue(script.contains("/api/conversations"));
		assertTrue(script.contains("/api/chat/stream"));
		assertTrue(script.contains("function exportData"));
		assertTrue(styles.contains(".message-editor"));
		assertTrue(styles.contains(".code-copy"));
	}

	private String readClasspathResource(String path) throws IOException {
		try (InputStream input = getClass().getResourceAsStream(path)) {
			assertNotNull(input, path + " should be available on the classpath");
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
