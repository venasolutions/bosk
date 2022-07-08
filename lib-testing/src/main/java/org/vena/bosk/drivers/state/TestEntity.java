package org.vena.bosk.drivers.state;

import java.util.Optional;
import lombok.Value;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;
import org.vena.bosk.Catalog;
import org.vena.bosk.Entity;
import org.vena.bosk.Identifier;
import org.vena.bosk.Listing;
import org.vena.bosk.Reference;
import org.vena.bosk.SideTable;

@Value
@Accessors(fluent = true)
@With
@FieldNameConstants
public class TestEntity implements Entity {
	Identifier id;
	String string;
	Catalog<TestEntity> catalog;
	Listing<TestEntity> listing;
	SideTable<TestEntity, TestEntity> sideTable;
	Optional<TestValues> values;

	public static TestEntity empty(Identifier id, Reference<Catalog<TestEntity>> catalogRef) {
		return new TestEntity(id,
			id.toString(),
			Catalog.empty(),
			Listing.empty(catalogRef),
			SideTable.empty(catalogRef),
			Optional.empty());
	}

}
