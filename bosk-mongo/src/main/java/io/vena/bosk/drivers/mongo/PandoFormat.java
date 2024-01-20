package io.vena.bosk.drivers.mongo;

import io.vena.bosk.ListValue;
import io.vena.bosk.StateTreeNode;
import java.util.Collection;

import static java.util.Arrays.asList;

/**
 * A scalable format that stores the bosk state in multiple documents,
 * thereby overcoming MongoDB's 16MB document size limit.
 *
 * <p>
 * Named after Pando: looks like a forest, but it's actually one tree with one root system.
 *
 * @param graftPoints A list of path strings pointing to {@link io.vena.bosk.Catalog Catalog}
 *                   or {@link io.vena.bosk.SideTable SideTable} tree nodes whose entries (children)
 *                   are to be stored in their own documents.
 */
public record PandoFormat(
	ListValue<String> graftPoints
) implements StateTreeNode, MongoDriverSettings.DatabaseFormat {
	/**
	 * Differs from Sequoia in that (1) the root document has a different ID, rendering the formats incompatible;
	 * and (2) Sequoia is designed not to need multi-document transactions.
	 */
	public static PandoFormat oneBigDocument() {
		return new PandoFormat(ListValue.empty());
	}

	public static PandoFormat withGraftPoints(Collection<String> pathStrings) {
		return new PandoFormat(ListValue.from(pathStrings));
	}

	public static PandoFormat withGraftPoints(String... pathStrings) {
		return withGraftPoints(asList(pathStrings));
	}
}
