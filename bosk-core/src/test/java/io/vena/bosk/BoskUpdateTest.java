package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.AbstractBoskTest.TestEnum.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * To get complete coverage of Bosk.java, include these:
 *
 * @see BoskConstructorTest
 * @see BoskLocalReferenceTest
 * @see HooksTest
 * @see ReferenceTest
 */
public class BoskUpdateTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;

	Reference<TestEntity> anyEntity;
	Reference<String> anyEntityString;
	Reference<TestChild> anyChild;
	Reference<Identifier> anyEntityID;

	TestRoot originalRoot;
	TestEntity originalParent;
	TestChild originalChild1;

	static final Identifier PARENT_ID = Identifier.from("parent");
	static final Identifier CHILD_1_ID = Identifier.from("child1");
	static final Identifier CHILD_4_ID = Identifier.from("child4"); // Initially nonexistent

	@BeforeEach
	void createBosk() throws InvalidTypeException {
		bosk = new Bosk<TestRoot>(
			BoskUpdateTest.class.getSimpleName(),
			TestRoot.class,
			AbstractBoskTest::initialRoot,
			Bosk::simpleDriver
		);
		anyEntity = bosk.reference(TestEntity.class, Path.of(
			TestRoot.Fields.entities, "-entity-"));
		anyEntityString = bosk.reference(String.class, Path.of(
			TestRoot.Fields.entities, "-entity-", TestEntity.Fields.string));
		anyEntityID = bosk.reference(Identifier.class, Path.of(
			TestRoot.Fields.entities, "-entity-", TestEntity.Fields.id));
		anyChild = bosk.reference(TestChild.class, Path.of(
			TestRoot.Fields.entities, "-entity-", TestEntity.Fields.children, "-child-"));
		try (val __ = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
			originalParent = anyEntity.boundTo(PARENT_ID).value();
			originalChild1 = anyChild.boundTo(PARENT_ID, CHILD_1_ID).value();
		}
	}

	@Test
	void initialRoot_matches() throws IOException, InterruptedException {
		assertValueEquals(initialRoot(bosk), bosk.rootReference());
	}

	@Test
	void replaceEntity_nodeChanged() throws IOException, InterruptedException {
		TestEntity newValue = originalParent.withString(originalParent.string() + " - modified");
		Reference<TestEntity> ref = anyEntity.boundTo(originalParent.id());
		bosk.driver().submitReplacement(ref, newValue);
		assertValueEquals(newValue, ref);
	}

	@Test
	void replaceField_valueChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = anyEntityString.boundTo(originalParent.id());
		bosk.driver().submitReplacement(ref, newValue);
		assertValueEquals(newValue, ref);
	}

	@Test
	void replaceNonexistentField_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = anyEntityString.boundTo(Identifier.from("nonexistent"));
		bosk.driver().submitReplacement(ref, newValue);
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(null, ref);
	}

	@Test
	void initializeNonexistent_nodeCreated() throws IOException, InterruptedException {
		TestChild newValue = new TestChild(CHILD_4_ID, "string", OK, Catalog.empty());
		Reference<TestChild> ref = anyChild.boundTo(PARENT_ID, CHILD_4_ID);
		bosk.driver().submitInitialization(ref, newValue);
		assertValueEquals(newValue, ref);
	}

	@Test
	void initializeExisting_nothingChanged() throws IOException, InterruptedException {
		TestChild newValue = new TestChild(CHILD_1_ID, "string", OK, Catalog.empty());
		Reference<TestChild> ref = anyChild.boundTo(PARENT_ID, CHILD_1_ID);

		// Child 1 already exists, so submitInitialization should have no effect
		bosk.driver().submitInitialization(ref, newValue);
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalChild1, ref);
	}

	@Test
	void conditionalReplaceIDMatches_valueChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = anyEntityString.boundTo(originalParent.id());
		Reference<Identifier> idRef = anyEntityID.boundTo(originalParent.id());
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, originalParent.id());
		assertValueEquals(newValue, ref);
	}

	@Test
	void conditionalReplaceIDMismatches_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = anyEntityString.boundTo(originalParent.id());
		Reference<Identifier> idRef = anyEntityID.boundTo(originalParent.id());
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, Identifier.from("nonexistent"));
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalParent.string(), ref);
	}

	@Test
	void conditionalReplaceIDMismatchesNull_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = anyEntityString.boundTo(originalParent.id());
		Reference<Identifier> idRef = anyEntityID.boundTo(originalParent.id());
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, null);
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalParent.string(), ref);
	}

	@Test
	void conditionalReplaceIDMatchesNull_valueChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = anyEntityString.boundTo(originalParent.id());
		Reference<Identifier> idRef = anyEntityID.boundTo(Identifier.from("nonexistent"));
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, null);
		assertValueEquals(newValue, ref);
	}

	@Test
	void conditionalReplaceIDMismatchesNonNull_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = anyEntityString.boundTo(originalParent.id());
		Reference<Identifier> idRef = anyEntityID.boundTo(Identifier.from("nonexistent"));
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, Identifier.from("someValue"));
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalParent.string(), ref);
	}

	<T> void assertValueEquals(T expected, Reference<T> ref) throws IOException, InterruptedException {
		bosk.driver().flush();
		try (val __ = bosk.readContext()) {
			assertEquals(expected, ref.valueIfExists());
		}
	}
}
