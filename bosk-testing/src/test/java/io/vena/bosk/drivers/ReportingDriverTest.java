package io.vena.bosk.drivers;

import io.vena.bosk.BoskDiagnosticContext;
import io.vena.bosk.Identifier;
import io.vena.bosk.ListingEntry;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.operations.SubmitConditionalDeletion;
import io.vena.bosk.drivers.operations.SubmitConditionalReplacement;
import io.vena.bosk.drivers.operations.SubmitDeletion;
import io.vena.bosk.drivers.operations.SubmitInitialization;
import io.vena.bosk.drivers.operations.SubmitReplacement;
import io.vena.bosk.drivers.operations.UpdateOperation;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NotYetImplementedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportingDriverTest extends AbstractDriverTest {
	List<UpdateOperation> ops;
	AtomicInteger numFlushes;
	Refs refs;
	BoskDiagnosticContext.DiagnosticScope diagnosticScope;
	MapValue<String> expectedAttributes;
	final Identifier id1 = Identifier.from("id1");
	final Identifier id2 = Identifier.from("id2");

	public interface Refs {
		@ReferencePath("/id")                  Reference<Identifier> id();
		@ReferencePath("/listing/-id-")        Reference<ListingEntry> entry(Identifier id);
		@ReferencePath("/catalog/-id-")        Reference<TestEntity> entity(Identifier id);
		@ReferencePath("/catalog/-id-/string") Reference<String> string(Identifier id);
	}

	@BeforeEach
	void setUp() throws InvalidTypeException {
		ops = new ArrayList<>();
		numFlushes = new AtomicInteger(0);
		setupBosksAndReferences(ReportingDriver.factory(ops::add, numFlushes::incrementAndGet));
		refs = bosk.buildReferences(Refs.class);
		bosk.driver().submitReplacement(refs.entity(id1), emptyEntityAt(refs.entity(id1)));
		ops.clear();
		diagnosticScope = bosk.diagnosticContext().withAttribute(ReportingDriverTest.class.getSimpleName(), "expectedValue");
		expectedAttributes = bosk.diagnosticContext().getAttributes();
	}

	@AfterEach
	void closeDiagnosticScope() {
		diagnosticScope.close();
		diagnosticScope = null;
	}

	@Test
	void initialRoot() {
		assertExpectedEvents();
		assertCorrectBoskContents();
	}

	@Test
	void submitReplacement() {
		Reference<String> ref = refs.string(id1);
		String newValue = "submitReplacement";
		bosk.driver().submitReplacement(ref, newValue);
		assertExpectedEvents(new SubmitReplacement<>(ref, newValue, expectedAttributes));
		assertNodeEquals(newValue, ref);
		assertCorrectBoskContents();
	}

	@Test
	void submitConditionalReplacement() {
		Reference<String> ref = refs.string(id1);
		String newValue = "submitConditionalReplacement";
		Reference<Identifier> precondition = refs.id();
		Identifier requiredValue = Identifier.from("root");
		bosk.driver().submitConditionalReplacement(ref, newValue, precondition, requiredValue);
		assertExpectedEvents(new SubmitConditionalReplacement<>(ref, newValue, precondition, requiredValue, expectedAttributes));
		assertNodeEquals(newValue, ref);
		assertCorrectBoskContents();
	}

	@Test
	void submitInitialization() {
		Reference<TestEntity> ref = refs.entity(id2);
		TestEntity newValue = emptyEntityAt(ref);
		bosk.driver().submitInitialization(ref, newValue);
		assertExpectedEvents(new SubmitInitialization<>(ref, newValue, expectedAttributes));
		assertNodeEquals(newValue, ref);
		assertCorrectBoskContents();
	}

	@Test
	void submitDeletion() {
		Reference<ListingEntry> ref = refs.entry(id1);
		bosk.driver().submitReplacement(ref, LISTING_ENTRY);
		assertExpectedEvents(new SubmitReplacement<>(ref, LISTING_ENTRY, expectedAttributes));
		assertNodeEquals(LISTING_ENTRY, ref);
		assertCorrectBoskContents();

		ops.clear();
		bosk.driver().submitDeletion(ref);
		assertExpectedEvents(new SubmitDeletion<>(ref, expectedAttributes));
		assertNodeEquals(null, ref);
		assertCorrectBoskContents();
	}

	@Test
	void submitConditionalDeletion() {
		Reference<ListingEntry> ref = refs.entry(id1);
		bosk.driver().submitReplacement(ref, LISTING_ENTRY);
		assertExpectedEvents(new SubmitReplacement<>(ref, LISTING_ENTRY, expectedAttributes));
		assertNodeEquals(LISTING_ENTRY, ref);
		assertCorrectBoskContents();

		ops.clear();
		Reference<Identifier> precondition = refs.id();
		Identifier requiredValue = Identifier.from("root");
		bosk.driver().submitConditionalDeletion(ref, precondition, requiredValue);
		assertExpectedEvents(new SubmitConditionalDeletion<>(ref, precondition, requiredValue, expectedAttributes));
		assertNodeEquals(null, ref);
		assertCorrectBoskContents();
	}

	@Test
	void flush() {
	}

	private void assertExpectedEvents(UpdateOperation... expectedOps) {
		try {
			bosk.driver().flush();
		} catch (IOException | InterruptedException e) {
			throw new NotYetImplementedException(e);
		}
		List<UpdateOperation> actual = ops.stream()
			.map(op -> op.withFilteredAttributes(expectedAttributes.keySet())) // Unexpected attributes are not grounds for failing the test
			.collect(Collectors.toList());
		assertEquals(asList(expectedOps), actual);
	}

	private <T> void assertNodeEquals(T expectedValue, Reference<T> location) {
		try (var __  = bosk.readContext()) {
			assertEquals(expectedValue, location.valueIfExists());
		}
	}
}
