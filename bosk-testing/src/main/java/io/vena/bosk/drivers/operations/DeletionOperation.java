package io.vena.bosk.drivers.operations;

public interface DeletionOperation<T> extends UpdateOperation {
	@Override
	default boolean matchesIfApplied(UpdateOperation other) {
		if (other instanceof DeletionOperation) {
			return this.target().equals(other.target());
		} else {
			return false;
		}
	}
}
