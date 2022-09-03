package io.vena.bosk;

/**
 * Called to indicate the hook's "scope object" may have been modified.
 */
public interface BoskHook<T> {
	/**
	 * @param reference points to an object that may have been modified, corresponding
	 * to the scope on which this hook was registered. The referenced object may or
	 * may not exist.
	 */
	void onChanged(Reference<T> reference);
}
