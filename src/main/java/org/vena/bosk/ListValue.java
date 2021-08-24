package org.vena.bosk;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * An immutable {@link List} that can be used in a {@link Bosk}.
 *
 * <p>
 * This is a "pseudo-primitive value" in the sense that there's no way to make a {@link Reference}
 * to an entry within a {@link ListValue}: they are updated and deleted as a unit.
 *
 * <p>
 * This is an "escape hatch" for when you just want to have a list that persists
 * in a Bosk. For most purposes, {@link Catalog} is more appropriate.
 *
 * <p>
 * The entries in the list must still be valid Bosk datatypes. This is not a
 * magic way to put arbitrary data structures into a Bosk.
 *
 * <p>
 * The constructor is protected so that you can make your own efficient
 * list-like value types that have additional properties.
 * <strong>But beware</strong>: if you modify the contents of the
 * <code>entries</code> array, you are embarking on an odyssey of vexation and
 * dismay that will make you question your sanity. Your application will not
 * work, and you will be unable to figure out why, because every part of the
 * Bosk library, and of every application written using the library, assume
 * objects are immutable. <strong>You have been warned.</strong>
 *
 * @author pdoyle
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class ListValue<T> extends AbstractList<T> {
	protected final T[] entries;

	@SuppressWarnings({ "unchecked" })
	public static <TT> ListValue<TT> empty() {
		return EMPTY;
	}

	@SafeVarargs
	public static <TT> ListValue<TT> of(TT... entries) {
		if (entries.length == 0) {
			return empty();
		} else {
			return new ListValue<>(entries.clone());
		}
	}

	@SuppressWarnings("unchecked")
	public static <TT> ListValue<TT> from(Collection<TT> entries) {
		if (entries.size() == 0) {
			return empty();
		} else {
			return new ListValue<>((TT[])entries.toArray());
		}
	}

	public static <TT> ListValue<TT> from(Stream<TT> entries) {
		return entries.collect(toListValue());
	}

	@Override
	public final int size() {
		return entries.length;
	}

	@Override
	public final T get(int index) {
		return entries[index];
	}

	@Override
	public final T[] toArray() {
		return entries.clone();
	}

	@Override
	public final String toString() {
		return Arrays.toString(entries);
	}

	public static <TT>
	Collector<TT, ?, ListValue<TT>> toListValue() {
		Function<List<TT>, ListValue<TT>> finisher = ListValue::from;
		return Collector.of(
				ArrayList::new,
				List::add,
				(left, right) -> { left.addAll(right); return left; },
				finisher);
	}

	@SuppressWarnings("rawtypes")
	private static final ListValue EMPTY = new ListValue<>(new Object[0]);

}
