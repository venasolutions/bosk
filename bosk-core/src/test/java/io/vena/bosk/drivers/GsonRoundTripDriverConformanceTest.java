package io.vena.bosk.drivers;

import com.google.gson.GsonBuilder;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.junit.ParametersByName;
import java.util.stream.Stream;

import static io.vena.bosk.AbstractRoundTripTest.gsonRoundTripFactory;
import static java.util.function.UnaryOperator.identity;

public class GsonRoundTripDriverConformanceTest extends DriverConformanceTest {
	@ParametersByName
	GsonRoundTripDriverConformanceTest(DriverFactory<TestEntity> driverFactory) {
		this.driverFactory = driverFactory;
	}

	static Stream<DriverFactory<TestEntity>> driverFactory() {
		return Stream.of(
			gsonRoundTripFactory(identity()),
			gsonRoundTripFactory(GsonBuilder::serializeNulls),
			gsonRoundTripFactory(GsonBuilder::excludeFieldsWithoutExposeAnnotation)
		);
	}
}
