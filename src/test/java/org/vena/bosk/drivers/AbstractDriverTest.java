package org.vena.bosk.drivers;

import java.io.IOException;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.CatalogReference;
import org.vena.bosk.Identifier;
import org.vena.bosk.Path;
import org.vena.bosk.Reference;
import org.vena.bosk.drivers.state.TestEntity;
import org.vena.bosk.exceptions.InvalidTypeException;

import static java.lang.Thread.currentThread;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AbstractDriverTest {
	protected final Identifier rootID = Identifier.from("root");
	protected final Identifier child1ID = Identifier.from("child1");
	protected final Identifier child2ID = Identifier.from("child2");
	protected Bosk<TestEntity> canonicalBosk;
	protected Bosk<TestEntity> bosk;
	protected BoskDriver<TestEntity> driver;

	protected void setupBosksAndReferences(BiFunction<BoskDriver<TestEntity>, Bosk<TestEntity>, BoskDriver<TestEntity>> driverFactory) {
		// This is the bosk whose behaviour we'll consider to be correct by definition
		canonicalBosk = new Bosk<TestEntity>("Canonical bosk", TestEntity.class, this::initialRoot, Bosk::simpleDriver);

		// This is the bosk we're testing
		bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, (d,b) -> new ForwardingDriver<>(asList(
			new MirroringDriver<>(canonicalBosk),
			driverFactory.apply(d, b)
		)));
		driver = bosk.driver();
	}

	@Nonnull
	private TestEntity initialRoot(Bosk<TestEntity> b) throws InvalidTypeException {
		return TestEntity.empty(rootID, b.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.catalog)));
	}

	TestEntity autoInitialize(Reference<TestEntity> ref) {
		if (ref.path().isEmpty()) {
			// Root always exists; nothing to do
			return null;
		} else {
			Reference<TestEntity> outer;
			try {
				outer = ref.enclosingReference(TestEntity.class);
			} catch (InvalidTypeException e) {
				throw new AssertionError("Every entity besides the root should be inside another entity", e);
			}
			autoInitialize(outer);
			TestEntity newEntity = emptyEntityAt(ref);
			driver.submitInitialization(ref, newEntity);
			return newEntity;
		}
	}

	@Nonnull
	private TestEntity emptyEntityAt(Reference<TestEntity> ref) {
		CatalogReference<TestEntity> catalogRef;
		try {
			catalogRef = ref.thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Every entity should have a catalog in it", e);
		}
		return TestEntity.empty(Identifier.from(ref.path().lastSegment()), catalogRef);
	}

	protected TestEntity newEntity(Identifier id, CatalogReference<TestEntity> enclosingCatalogRef) throws InvalidTypeException {
		return TestEntity.empty(id, enclosingCatalogRef.then(id).thenCatalog(TestEntity.class, TestEntity.Fields.catalog));
	}

	void assertCorrectBoskContents() {
		try {
			driver.flush();
		} catch (InterruptedException e) {
			currentThread().interrupt();
			throw new AssertionError("Unexpected interruption", e);
		} catch (IOException e) {
			throw new AssertionError("Unexpected exception", e);
		}
		TestEntity expected, actual;
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = canonicalBosk.readContext()) {
			expected = canonicalBosk.rootReference().value();
		}
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = bosk.readContext()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

}
