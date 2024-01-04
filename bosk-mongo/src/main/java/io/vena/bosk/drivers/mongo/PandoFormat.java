package io.vena.bosk.drivers.mongo;

import io.vena.bosk.ListValue;
import io.vena.bosk.StateTreeNode;
import java.util.Collection;
import lombok.Value;

import static java.util.Arrays.asList;

/**
 * A scalable format  that stores the bosk state in multiple documents,
 * thereby overcoming MongoDB's 16MB document size limit.
 */
public record PandoFormat(
	ListValue<String> separateCollections
) implements StateTreeNode, MongoDriverSettings.DatabaseFormat {
	/**
	 * Differs from Sequoia in that (1) the root document has a different ID, rendering the formats incompatible;
	 * and (2) Sequoia is designed not to need multi-document transactions.
	 */
	public static PandoFormat oneBigDocument() {
		return new PandoFormat(ListValue.empty());
	}

	public static PandoFormat withSeparateCollections(Collection<String> separateCollections) {
		return new PandoFormat(ListValue.from(separateCollections));
	}

	public static PandoFormat withSeparateCollections(String... pathStrings) {
		return withSeparateCollections(asList(pathStrings));
	}
}
