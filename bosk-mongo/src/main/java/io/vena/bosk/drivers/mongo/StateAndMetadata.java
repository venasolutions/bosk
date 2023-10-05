package io.vena.bosk.drivers.mongo;

import io.vena.bosk.MapValue;
import io.vena.bosk.StateTreeNode;
import lombok.RequiredArgsConstructor;
import org.bson.BsonInt64;

@RequiredArgsConstructor
class StateAndMetadata<R extends StateTreeNode> {
	final R state;
	final BsonInt64 revision;
	final MapValue<String> diagnosticAttributes;
}
