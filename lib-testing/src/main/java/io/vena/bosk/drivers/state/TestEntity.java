package io.vena.bosk.drivers.state;

import io.vena.bosk.Catalog;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Listing;
import io.vena.bosk.Reference;
import io.vena.bosk.SideTable;
import java.util.Optional;
import lombok.Value;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;

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
