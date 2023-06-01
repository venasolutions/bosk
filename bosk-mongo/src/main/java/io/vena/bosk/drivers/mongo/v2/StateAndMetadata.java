package io.vena.bosk.drivers.mongo.v2;

import io.vena.bosk.Entity;
import lombok.RequiredArgsConstructor;
import org.bson.BsonInt64;

@RequiredArgsConstructor
class StateAndMetadata<R extends Entity> {
	final R state;
	final BsonInt64 revision;
}
