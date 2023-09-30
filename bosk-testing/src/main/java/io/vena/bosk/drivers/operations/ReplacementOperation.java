package io.vena.bosk.drivers.operations;

public interface ReplacementOperation<T> extends UpdateOperation {
	T newValue();

	@Override
	default boolean matchesIfApplied(UpdateOperation other) {
		if (other instanceof ReplacementOperation) {
			return this.target().equals(other.target())
				&& this.newValue().equals(((ReplacementOperation<?>) other).newValue());
		} else {
			return false;
		}
	}
}
