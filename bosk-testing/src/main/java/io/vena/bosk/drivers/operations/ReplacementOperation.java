package io.vena.bosk.drivers.operations;

public sealed interface ReplacementOperation<T> extends UpdateOperation permits
	SubmitConditionalReplacement,
	SubmitInitialization,
	SubmitReplacement
{
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
