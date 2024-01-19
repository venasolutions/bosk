package io.vena.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;

public class AsyncDriverConformanceTest extends DriverConformanceTest {

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = AsyncDriver.factory();
	}

}
