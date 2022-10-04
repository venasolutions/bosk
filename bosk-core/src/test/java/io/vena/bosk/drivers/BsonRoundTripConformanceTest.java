package io.vena.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;

import static io.vena.bosk.AbstractRoundTripTest.bsonRoundTripFactory;

public class BsonRoundTripConformanceTest extends DriverConformanceTest {
	@BeforeEach
	void setupDriverFactory() {
		driverFactory = bsonRoundTripFactory();
	}
}
