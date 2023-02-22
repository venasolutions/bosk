package io.vena.bosk;

import io.vena.bosk.AbstractBoskTest.ImplicitRefs;
import io.vena.bosk.AbstractBoskTest.Optionals;
import io.vena.bosk.AbstractBoskTest.Phantoms;
import io.vena.bosk.AbstractBoskTest.TestChild;
import io.vena.bosk.AbstractBoskTest.TestEntity;
import io.vena.bosk.AbstractBoskTest.TestEnum;
import io.vena.bosk.AbstractBoskTest.TestRoot;
import io.vena.bosk.exceptions.InvalidTypeException;

public class TestEntityBuilder {
	private final CatalogReference<TestEntity> entitiesRef;
	private final Reference<TestEntity> anyEntity;
	private final CatalogReference<TestChild> anyChildren;
	private final Reference<ImplicitRefs> anyImplicitRefs;

	public TestEntityBuilder(Bosk<TestRoot> bosk) throws InvalidTypeException {
		this.entitiesRef = bosk.rootReference().thenCatalog(TestEntity.class, TestRoot.Fields.entities);
		this.anyEntity = bosk.rootReference().then(TestEntity.class, TestRoot.Fields.entities, "-entity-");
		this.anyChildren = anyEntity.thenCatalog(TestChild.class, TestEntity.Fields.children);
		this.anyImplicitRefs = anyEntity.then(ImplicitRefs.class, TestEntity.Fields.implicitRefs);
	}

	public CatalogReference<TestEntity> entitiesRef() { return entitiesRef; }
	public Reference<TestEntity> entityRef(Identifier id) { return anyEntity.boundTo(id); }
	public CatalogReference<TestChild> childrenRef(Identifier entityID) { return anyChildren.boundTo(entityID); }
	public Reference<ImplicitRefs> implicitRefsRef(Identifier entityID) { return anyImplicitRefs.boundTo(entityID); }

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
