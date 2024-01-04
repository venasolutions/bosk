package io.vena.bosk.drivers.operations;

public interface ReplacementOperation<T> extends UpdateOperation {
	T newValue();

	@Override
	default boolean matchesIfApplied(UpdateOperation other) {
		if (other instanceof ReplacementOperation<?> r) {
			return this.target().equals(other.target())
				&& this.newValue().equals(r.newValue());
		} else {
			return false;
		}
	}
}
