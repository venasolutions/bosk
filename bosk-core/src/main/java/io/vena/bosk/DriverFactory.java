package io.vena.bosk;

public interface DriverFactory<R extends StateTreeNode> {
	BoskDriver<R> build(BoskInfo<R> boskInfo, BoskDriver<R> downstream);
}
