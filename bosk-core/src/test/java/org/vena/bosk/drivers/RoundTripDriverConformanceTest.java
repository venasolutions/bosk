package org.vena.bosk.drivers;

import com.google.gson.GsonBuilder;
import java.util.stream.Stream;
import org.vena.bosk.DriverFactory;
import org.vena.bosk.Path;
import org.vena.bosk.drivers.state.TestEntity;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.junit.ParametersByName;

import static java.util.function.UnaryOperator.identity;
import static org.vena.bosk.AbstractRoundTripTest.bsonRoundTripFactory;
import static org.vena.bosk.AbstractRoundTripTest.gsonRoundTripFactory;

public class RoundTripDriverConformanceTest extends DriverConformanceTest {
	@ParametersByName
	RoundTripDriverConformanceTest(DriverFactory<TestEntity> driverFactory) {
		this.driverFactory = driverFactory;
	}

	static Stream<DriverFactory<TestEntity>> driverFactory() {
		return Stream.of(
			bsonRoundTripFactory(),
			gsonRoundTripFactory(identity()),
			gsonRoundTripFactory(GsonBuilder::serializeNulls),
			gsonRoundTripFactory(GsonBuilder::excludeFieldsWithoutExposeAnnotation)
		);
	}

	@ParametersByName(singleInvocationIndex = 11)
	void oneOffTest(Path enclosingCatalogPath) throws InvalidTypeException {
		testDeleteNonexistent(enclosingCatalogPath);
	}
}
