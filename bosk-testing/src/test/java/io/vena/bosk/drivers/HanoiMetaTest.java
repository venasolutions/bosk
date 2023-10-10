package io.vena.bosk.drivers;

import io.vena.bosk.Bosk;

public class HanoiMetaTest extends HanoiTest {
	public HanoiMetaTest() {
		driverFactory = Bosk::simpleDriver;
	}
}
