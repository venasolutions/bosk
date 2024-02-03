package io.vena.bosk.drivers.mongo.status;

import static io.vena.bosk.drivers.mongo.status.Difference.prefixed;

public record UnexpectedNode(
	String bsonPath
) implements SomeDifference {
	@Override
	public UnexpectedNode withPrefix(String prefix) {
		return new UnexpectedNode(prefixed(prefix, bsonPath));
	}
}
