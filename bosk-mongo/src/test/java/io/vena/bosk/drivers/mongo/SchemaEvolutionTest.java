package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.Reference;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.ManifestMode;
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
	SchemaEvolutionTest(ManifestMode fromMode, ManifestMode toMode) {
		fromHelper = new Helper(fromMode);
		toHelper = new Helper(toMode);
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

	@SuppressWarnings("unused")
	static Stream<ManifestMode> fromMode() {
		return Stream.of(ManifestMode.values());
	}

	@SuppressWarnings("unused")
	static Stream<ManifestMode> toMode() {
		return fromMode();
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
		return new Bosk<TestEntity>(helper.name, TestEntity.class, helper::initialRoot, helper.driverFactory);
	}

	static final class Helper extends AbstractMongoDriverTest {
		final String name;

		public Helper(ManifestMode manifestMode) {
			super(MongoDriverSettings.builder()
				.database(SchemaEvolutionTest.class.getSimpleName())
				.experimental(Experimental.builder()
					.manifestMode(manifestMode)
					.build())
			);
			this.name = manifestMode.name();
		}
	}

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}
}
