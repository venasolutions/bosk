package io.vena.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;

public class BufferingDriverConformanceTest extends DriverConformanceTest {

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = (b,d)-> BufferingDriver.writingTo(d);
	}

}
