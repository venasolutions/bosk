package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.Reference;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.util.stream.Stream;
import lombok.var;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;

@UsesMongoService
public class SchemaEvolutionTest {

	private final Helper fromHelper;
	private final Helper toHelper;

	@ParametersByName
	SchemaEvolutionTest(ImplementationKind fromKind, ImplementationKind toKind) {
		fromHelper = new Helper(fromKind);
		toHelper = new Helper(toKind);
	}

	@BeforeAll
	static void beforeAll() {
		AbstractMongoDriverTest.setupMongoConnection();
	}

	@BeforeEach
	void beforeEach(TestInfo testInfo) {
		fromHelper.setupDriverFactory();
		toHelper  .setupDriverFactory();

		fromHelper.clearTearDown(testInfo);
		toHelper  .clearTearDown(testInfo);
	}

	@AfterEach
	void afterEach(TestInfo testInfo) {
		fromHelper.runTearDown(testInfo);
		toHelper  .runTearDown(testInfo);
	}

	static Stream<ImplementationKind> fromKind() {
		return Stream.of(ImplementationKind.values());
	}

	static Stream<ImplementationKind> toKind() {
		return Stream.of(ImplementationKind.values());
	}

	@ParametersByName
	void pairwiseImplementationKinds_compatible() throws InvalidTypeException {
		Bosk<TestEntity> fromBosk = newBosk(fromHelper);
		Refs fromRefs = fromBosk.buildReferences(Refs.class);
		fromBosk.driver().submitReplacement(fromRefs.string(), "Distinctive String");

		Bosk<TestEntity> toBosk = newBosk(toHelper);
		Refs toRefs = toBosk.buildReferences(Refs.class);

		try (var __ = toBosk.readContext()) {
			assertEquals("Distinctive String", toRefs.string().value());
		}
	}

	private static Bosk<TestEntity> newBosk(Helper helper) {
		return new Bosk<TestEntity>(helper.kind.name(), TestEntity.class, helper::initialRoot, helper.driverFactory);
	}

	static final class Helper extends AbstractMongoDriverTest {
		final ImplementationKind kind;

		public Helper(ImplementationKind kind) {
			super(MongoDriverSettings.builder()
				.database(SchemaEvolutionTest.class.getSimpleName())
				.experimental(Experimental.builder()
					.implementationKind(kind)
					.build())
			);
			this.kind = kind;
		}
	}

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}
}
