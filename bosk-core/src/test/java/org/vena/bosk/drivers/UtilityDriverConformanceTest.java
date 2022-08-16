package org.vena.bosk.drivers;

import java.util.stream.Stream;
import org.vena.bosk.DriverFactory;
import org.vena.bosk.drivers.state.TestEntity;
import org.vena.bosk.junit.ParametersByName;

import static java.util.Collections.singletonList;

public class UtilityDriverConformanceTest extends DriverConformanceTest {
	@ParametersByName
	public UtilityDriverConformanceTest(DriverFactory<TestEntity> driverFactory) {
		this.driverFactory = driverFactory;
	}

	static Stream<DriverFactory<TestEntity>> driverFactory() {
		return Stream.of(
			(b,d)-> new BufferingDriver<>(d),
			(b,d)-> new ForwardingDriver<>(singletonList(d))
		);
	}
}
