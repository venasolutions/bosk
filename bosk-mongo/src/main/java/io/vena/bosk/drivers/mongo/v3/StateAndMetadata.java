package io.vena.bosk.drivers.mongo.v3;

import io.vena.bosk.StateTreeNode;
import lombok.RequiredArgsConstructor;
import org.bson.BsonInt64;

@RequiredArgsConstructor
class StateAndMetadata<R extends StateTreeNode> {
	final R state;
	final BsonInt64 revision;
}
