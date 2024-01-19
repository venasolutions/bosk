package io.vena.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;

class JitterDriverConformanceTest extends DriverConformanceTest {
	@BeforeEach
	void setupDriverFactory() {
		driverFactory = JitterDriver.factory(0.1, 0.5, 123);
	}
}
