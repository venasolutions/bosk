package io.vena.bosk.drivers;

import com.google.gson.GsonBuilder;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Path;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.util.stream.Stream;

import static io.vena.bosk.AbstractRoundTripTest.bsonRoundTripFactory;
import static io.vena.bosk.AbstractRoundTripTest.gsonRoundTripFactory;
import static java.util.function.UnaryOperator.identity;

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
