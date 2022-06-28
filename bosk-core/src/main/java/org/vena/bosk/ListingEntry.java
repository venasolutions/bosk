package org.vena.bosk;

/**
 * Used as the {@link Reference#value value} of a {@link Reference} to an entry in a {@link Listing}.
 * Pass {@link #LISTING_ENTRY} as the value in {@link BoskDriver#submitReplacement}
 * to add an entry to a Listing; use {@link BoskDriver#submitDeletion} to remove it.
 *
 * <p>
 * Formally speaking, this is a "unit type" that carries no information.
 */
public final class ListingEntry {
	private ListingEntry(){}

	/**
	 * Represents the existence of an entry in a {@link Listing}.
	 * Returned by {@link Reference#value()} for a reference to a Listing entry that exists.
	 */
	@SuppressWarnings("InstantiationOfUtilityClass")
	public static final ListingEntry LISTING_ENTRY = new ListingEntry();
}
