package io.vena.bosk.drivers.mongo;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
@Builder
public class MongoDriverSettings {
	String database;

	@Default String collection = "boskCollection";
	@Default long flushTimeoutMS = 30_000;
}
