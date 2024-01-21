package io.vena.bosk.drivers.mongo;

import io.vena.bosk.MapValue;
import io.vena.bosk.StateTreeNode;
import org.bson.BsonInt64;

record StateAndMetadata<R extends StateTreeNode>(
	R state,
	BsonInt64 revision,
	MapValue<String> diagnosticAttributes
) { }
