package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import lombok.val;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PluginRoundTripTest extends AbstractRoundTripTest {
	@ParameterizedTest
	@MethodSource("driverFactories")
	void testRoundTrip(DriverFactory<TestRoot> driverFactory) throws InvalidTypeException {
		Bosk<TestRoot> bosk = setUpBosk(driverFactory);
		TestRoot originalRoot;
		try (val context = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
		}
		bosk.driver().submitReplacement(bosk.rootReference(), originalRoot);

		try (val context = bosk.readContext()) {
			// Use our entity's equals() to check that all is well
			//
			assertEquals(originalRoot, bosk.rootReference().value());

			// Ensure enclosing references point to the right entities
			//
			Reference<TestEntity> parentRef = bosk.rootReference().then(TestEntity.class, "entities", "parent");
			assertEquals(parentRef.then(ImplicitRefs.class, "implicitRefs"), parentRef.value().implicitRefs().reference());
			assertEquals(parentRef, parentRef.value().implicitRefs().enclosingRef());
		}
	}

}
