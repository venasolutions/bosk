package org.vena.bosk.drivers;

import org.junit.jupiter.api.BeforeEach;
import org.vena.bosk.Bosk;

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
