package io.vena.bosk;

import io.vena.bosk.AbstractBoskTest.ImplicitRefs;
import io.vena.bosk.AbstractBoskTest.Optionals;
import io.vena.bosk.AbstractBoskTest.Phantoms;
import io.vena.bosk.AbstractBoskTest.TestChild;
import io.vena.bosk.AbstractBoskTest.TestEntity;
import io.vena.bosk.AbstractBoskTest.TestEnum;
import io.vena.bosk.AbstractBoskTest.TestRoot;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.exceptions.InvalidTypeException;

public class TestEntityBuilder {
	public final Refs refs;

	public interface Refs {
		@ReferencePath("/entities") CatalogReference<TestEntity> entitiesRef();
		@ReferencePath("/entities/-entity-") Reference<TestEntity> anyEntity();
		@ReferencePath("/entities/-entity-") Reference<TestEntity> entityRef(Identifier entity);
		@ReferencePath("/entities/-entity-/children") CatalogReference<TestChild> childrenRef(Identifier entity);
		@ReferencePath("/entities/-entity-/implicitRefs") Reference<ImplicitRefs> implicitRefsRef(Identifier entity);
	}

	public TestEntityBuilder(Bosk<TestRoot> bosk) throws InvalidTypeException {
		this.refs = bosk.buildReferences(Refs.class);
	}

	public CatalogReference<TestEntity> entitiesRef() { return refs.entitiesRef(); }
	public Reference<TestEntity> anyEntity() { return refs.anyEntity(); }
	public Reference<TestEntity> entityRef(Identifier id) { return refs.entityRef(id); }
	public CatalogReference<TestChild> childrenRef(Identifier entityID) { return refs.childrenRef(entityID); }
	public Reference<ImplicitRefs> implicitRefsRef(Identifier entityID) { return refs.implicitRefsRef(entityID); }

	public TestEntity blankEntity(Identifier id, TestEnum testEnum) {
		return new TestEntity(id,
			id.toString(),
			testEnum,
			Catalog.empty(),
			Listing.empty(childrenRef(id)),
			SideTable.empty(childrenRef(id)),
			Phantoms.empty(Identifier.from(id + "_phantoms")),
			Optionals.empty(Identifier.from(id + "_optionals")),
			new ImplicitRefs(Identifier.from(id + "_implicitRefs"),
				implicitRefsRef(id), entityRef(id),
				implicitRefsRef(id), entityRef(id)));
	}

}
