package io.vena.bosk.drivers.mongo.status;

import static io.vena.bosk.drivers.mongo.status.Difference.prefixed;

public record PrimitiveDifference(
	String bsonPath
) implements SomeDifference {
	@Override
	public PrimitiveDifference withPrefix(String prefix) {
		return new PrimitiveDifference(prefixed(prefix, bsonPath));
	}
}
