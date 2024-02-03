package io.vena.bosk.drivers.mongo.status;

import java.util.List;

import static io.vena.bosk.drivers.mongo.status.Difference.prefixed;

public record MultipleDifferences(
	String bsonPath,
	List<Difference> examples
) implements SomeDifference {
	@Override
	public MultipleDifferences withPrefix(String prefix) {
		return new MultipleDifferences(prefixed(prefix, bsonPath), this.examples);
	}
}
