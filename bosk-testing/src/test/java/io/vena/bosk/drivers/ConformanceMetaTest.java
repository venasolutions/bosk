package io.vena.bosk.drivers;

import io.vena.bosk.Bosk;
import org.junit.jupiter.api.BeforeEach;

/**
 * Makes sure {@link DriverConformanceTest} works properly by testing
 * {@link Bosk#simpleDriver} against itself.
 */
public class ConformanceMetaTest extends DriverConformanceTest {

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = Bosk::simpleDriver;
	}

}
