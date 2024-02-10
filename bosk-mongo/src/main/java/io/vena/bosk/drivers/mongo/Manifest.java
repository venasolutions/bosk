package io.vena.bosk.drivers.mongo;

import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import java.util.Optional;

public record Manifest(
	Integer version,
	Optional<EmptyNode> sequoia,
	Optional<PandoFormat> pando
) implements StateTreeNode {
	public record EmptyNode() implements StateTreeNode { }

	public static Manifest forSequoia() {
		return new Manifest(1, Optional.of(new EmptyNode()), Optional.empty());
	}

	public static Manifest forPando(PandoFormat settings) {
		return new Manifest(1, Optional.empty(), Optional.of(settings));
	}

	public static Manifest forFormat(DatabaseFormat format) {
		// TODO: Use a type switch in newer Java versions
		if (format instanceof PandoFormat p) {
			return forPando(p);
		} else {
			return forSequoia();
		}
	}
}
