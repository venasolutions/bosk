package io.vena.bosk.drivers.mongo;

import io.vena.bosk.ListValue;
import io.vena.bosk.Path;
import io.vena.bosk.StateTreeNode;
import java.util.Collection;
import lombok.Value;

@Value
public class PandoFormat implements StateTreeNode, MongoDriverSettings.DatabaseFormat {
	ListValue<String> separateCollections;

	public static PandoFormat oneBigDocument() {
		return new PandoFormat(ListValue.empty());
	}

	public static PandoFormat withSeparateCollections(Collection<String> separateCollections) {
		return new PandoFormat(ListValue.from(separateCollections));
	}
}
