package io.vena.bosk;

public interface DriverFactory<R extends StateTreeNode> {
	BoskDriver<R> build(Bosk<R> bosk, BoskDriver<R> downstream);
}
