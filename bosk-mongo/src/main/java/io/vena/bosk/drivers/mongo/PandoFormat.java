package io.vena.bosk.drivers.mongo;

import io.vena.bosk.ListValue;
import io.vena.bosk.StateTreeNode;
import java.util.Collection;
import lombok.Value;

@Value
public class PandoFormat implements StateTreeNode, MongoDriverSettings.DatabaseFormat {
	ListValue<String> separateCollections;

	/**
	 * Differs from Sequoia in that (1) the root document has a different ID, rendering the formats incompativle;
	 * and (2) Sequoia is designed not to need multi-document transactions.
	 */
	public static PandoFormat oneBigDocument() {
		return new PandoFormat(ListValue.empty());
	}

	public static PandoFormat withSeparateCollections(Collection<String> separateCollections) {
		return new PandoFormat(ListValue.from(separateCollections));
	}
}
