package org.vena.bosk.drivers.mongo;

import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
@Builder
public class MongoDriverSettings {
	String database;
	String collection;
	@Builder.Default
	long flushTimeoutMS = 30_000;
}
