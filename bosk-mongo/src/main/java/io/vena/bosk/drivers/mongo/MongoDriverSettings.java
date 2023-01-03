package io.vena.bosk.drivers.mongo;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;

@Value
@Builder
public class MongoDriverSettings {
	String database;

	@Default long flushTimeoutMS = 30_000;
}
