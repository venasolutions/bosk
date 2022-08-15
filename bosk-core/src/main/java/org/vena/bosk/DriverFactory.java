package org.vena.bosk;

public interface DriverFactory<R extends Entity> {
	BoskDriver<R> apply(Bosk<R> bosk, BoskDriver<R> downstream);
}
