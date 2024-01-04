package io.vena.bosk.drivers.mongo;

import io.vena.bosk.StateTreeNode;
import java.util.Optional;
import lombok.Value;

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
}
