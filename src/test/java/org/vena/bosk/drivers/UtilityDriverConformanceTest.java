package org.vena.bosk.drivers;

import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.drivers.state.TestEntity;
import org.vena.bosk.junit.ParametersByName;

import static java.util.Collections.singletonList;

public class UtilityDriverConformanceTest extends DriverConformanceTest {
	@ParametersByName
	public UtilityDriverConformanceTest(BiFunction<BoskDriver<TestEntity>, Bosk<TestEntity>, BoskDriver<TestEntity>> driverFactory) {
		this.driverFactory = driverFactory;
	}

	static Stream<BiFunction<BoskDriver<TestEntity>, Bosk<TestEntity>, BoskDriver<TestEntity>>> driverFactory() {
		return Stream.of(
			(d,b)-> new BufferingDriver<>(d),
			(d,b)-> new ForwardingDriver<>(singletonList(d))
		);
	}
}
