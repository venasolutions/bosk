package io.vena.bosk.spring.boot;

import lombok.Value;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bosk.web")
@Value
@Accessors(fluent = false)
public class WebProperties {
	Boolean readContext;
	String servicePath;
}
