package com.zhida;

import com.zhida.config.ChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ChatProperties.class)
public class ZhidaApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZhidaApplication.class, args);
	}

}
