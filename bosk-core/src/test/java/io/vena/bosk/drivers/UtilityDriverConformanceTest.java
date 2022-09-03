package io.vena.bosk.drivers;

import io.vena.bosk.DriverFactory;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.junit.ParametersByName;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;

public class UtilityDriverConformanceTest extends DriverConformanceTest {
	@ParametersByName
	public UtilityDriverConformanceTest(DriverFactory<TestEntity> driverFactory) {
		this.driverFactory = driverFactory;
	}

	static Stream<DriverFactory<TestEntity>> driverFactory() {
		return Stream.of(
			(b,d)-> BufferingDriver.writingTo(d),
			(b,d)-> new ForwardingDriver<>(singletonList(d))
		);
	}
}
