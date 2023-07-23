package io.vena.bosk.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vena.bosk.Bosk;
import io.vena.bosk.jackson.BoskJacksonModule;
import io.vena.bosk.jackson.JacksonPlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WebProperties.class)
public class BoskAutoConfiguration {
	@Bean
	@ConditionalOnProperty(
		prefix = "bosk.web",
		name = "read-context",
		matchIfMissing = true)
	@ConditionalOnBean(Bosk.class) // Because of matchIfMissing
	ReadContextFilter readContextFilter(
		Bosk<?> bosk
	) {
		return new ReadContextFilter(bosk);
	}

	@Bean
	@ConditionalOnProperty(prefix = "bosk.web", name = "service-path")
	ServiceEndpoints serviceEndpoints(
		Bosk<?> bosk,
		ObjectMapper mapper,
		JacksonPlugin plugin,
		@Value("${bosk.web.service-path}") String contextPath
	) {
		return new ServiceEndpoints(bosk, mapper, plugin, contextPath);
	}

	@Bean
	@ConditionalOnMissingBean
	JacksonPlugin jacksonPlugin() {
		return new JacksonPlugin();
	}

	@Bean
	BoskJacksonModule boskJacksonModule(Bosk<?> bosk, JacksonPlugin jacksonPlugin) {
		return jacksonPlugin.moduleFor(bosk);
	}

}
