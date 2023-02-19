package io.vena.bosk.drivers;

import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.DriverStack;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Thread.currentThread;
import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class AbstractDriverTest {
	protected final Identifier child1ID = Identifier.from("child1");
	protected final Identifier child2ID = Identifier.from("child2");
	protected Bosk<TestEntity> canonicalBosk;
	protected Bosk<TestEntity> bosk;
	protected BoskDriver<TestEntity> driver;

	@BeforeEach
	void logStart(TestInfo testInfo) {
		logTest("/=== Start", testInfo);
	}

	@AfterEach
	void logDone(TestInfo testInfo) {
		logTest("\\=== Done", testInfo);
	}

	private static void logTest(String verb, TestInfo testInfo) {
		String method =
			testInfo.getTestClass().map(Class::getSimpleName).orElse(null)
				+ "."
				+ testInfo.getTestMethod().map(Method::getName).orElse(null);
		LOGGER.info("{} {} {}", verb, method, testInfo.getDisplayName());
	}

	protected void setupBosksAndReferences(DriverFactory<TestEntity> driverFactory) {
		// This is the bosk whose behaviour we'll consider to be correct by definition
		canonicalBosk = new Bosk<TestEntity>("Canonical bosk", TestEntity.class, AbstractDriverTest::initialRoot, Bosk::simpleDriver);

		// This is the bosk we're testing
		bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, AbstractDriverTest::initialRoot, DriverStack.of(
			MirroringDriver.targeting(canonicalBosk),
			driverFactory
		));
		driver = bosk.driver();
	}

	private static TestEntity initialRoot(Bosk<TestEntity> b) throws InvalidTypeException {
		return TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, Path.just(TestEntity.Fields.catalog)));
	}

	protected TestEntity autoInitialize(Reference<TestEntity> ref) {
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

	TestEntity emptyEntityAt(Reference<TestEntity> ref) {
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

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDriverTest.class);
}
