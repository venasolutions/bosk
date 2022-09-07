package io.vena.bosk;

public interface DriverFactory<R extends Entity> {
	BoskDriver<R> build(Bosk<R> bosk, BoskDriver<R> downstream);
}
