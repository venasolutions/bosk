package io.vena.bosk.drivers.operations;

public sealed interface DeletionOperation<T> extends UpdateOperation permits
	SubmitConditionalDeletion,
	SubmitDeletion
{
	@Override
	default boolean matchesIfApplied(UpdateOperation other) {
		if (other instanceof DeletionOperation) {
			return this.target().equals(other.target());
		} else {
			return false;
		}
	}
}
