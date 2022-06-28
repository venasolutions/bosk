package org.vena.bosk.dereferencers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;
import org.vena.bosk.Catalog;
import org.vena.bosk.Entity;
import org.vena.bosk.Identifier;
import org.vena.bosk.Listing;
import org.vena.bosk.ListingEntry;
import org.vena.bosk.Path;
import org.vena.bosk.Phantom;
import org.vena.bosk.Reference;
import org.vena.bosk.SideTable;
import org.vena.bosk.StateTreeNode;
import org.vena.bosk.bytecode.LocalVariable;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.TunneledCheckedException;

import static java.util.Collections.synchronizedMap;
import static java.util.Locale.ROOT;
import static lombok.AccessLevel.PRIVATE;
import static org.vena.bosk.Path.isParameterSegment;
import static org.vena.bosk.ReferenceUtils.getterMethod;
import static org.vena.bosk.ReferenceUtils.gettersForConstructorParameters;
import static org.vena.bosk.ReferenceUtils.parameterType;
import static org.vena.bosk.ReferenceUtils.rawClass;
import static org.vena.bosk.ReferenceUtils.theOnlyConstructorFor;
import static org.vena.bosk.bytecode.ClassBuilder.here;

/**
 * Compiles {@link Path} objects into {@link Dereferencer}s for a given source {@link Type}.
 *
 * <p>
 * <em>Implementation note:</em> This class has some pretty deep inner-class nesting, because
 * a lot of these classes need context from their outer class. Some could be written another way;
 * others really can't. Whether you find this objectionable depends on your level of distaste
 * for inner classes.
 */
@RequiredArgsConstructor(access = PRIVATE)
public final class PathCompiler {
	private final Type sourceType;
	private final Map<Path, DereferencerBuilder> memoizedBuilders = synchronizedMap(new WeakHashMap<>());
	private final Map<DereferencerBuilder, Dereferencer> memoizedDereferencers = synchronizedMap(new WeakHashMap<>());

	private static final Map<Type, PathCompiler> compilersByType = new ConcurrentHashMap<>();

	public static PathCompiler withSourceType(Type sourceType) {
		/* We instantiate just one PathCompiler per sourceType.

		PathCompiler takes a few seconds to warm up because of all
		the class loading.  This generally doesn't matter in
		production, because you have a small number of Bosks
		(usually just one) and once it warms up, it's fast.
		But for unit tests, we make hundreds of Bosks, so sharing
		their PathCompilers makes the tests much, much faster.
		 */
		return compilersByType.computeIfAbsent(sourceType, PathCompiler::new);
	}

	public Dereferencer compiled(Path path) throws InvalidTypeException {
		try {
			return memoizedDereferencers.computeIfAbsent(builderFor(path), DereferencerBuilder::buildInstance);
		} catch (TunneledCheckedException e) {
			throw e.getCause(InvalidTypeException.class);
		}
	}

	public Path fullyParameterizedPathOf(Path path) throws InvalidTypeException {
		try {
			return builderFor(path).fullyParameterizedPath();
		} catch (TunneledCheckedException e) {
			throw e.getCause(InvalidTypeException.class);
		}
	}

	public Type targetTypeOf(Path path) throws InvalidTypeException {
		try {
			return builderFor(path).targetType();
		} catch (TunneledCheckedException e) {
			throw e.getCause(InvalidTypeException.class);
		}
	}

	private DereferencerBuilder builderFor(Path path) throws TunneledCheckedException {
		// We'd like to use computeIfAbsent for this, but we can't,
		// because in some cases we need to add two entries to the map
		// (for the given path and the fully parameterized one)
		// and computeIfAbsent can't do that.
		DereferencerBuilder result = memoizedBuilders.get(path);
		if (result == null) {
			result = computeBuilder(path);
			DereferencerBuilder previous = memoizedBuilders.putIfAbsent(path, result);
			if (previous != null) {
				// Must not switch to a new DereferencerBuilder, even if it's equivalent.
				// We guarantee that we'll always use the same one.
				return previous;
			}
		}
		return result;
	}

	/**
	 * Note: also memoizes the resulting builder under the fully parameterized path
	 * if different from the given path.
	 */
	private DereferencerBuilder computeBuilder(Path path) throws TunneledCheckedException {
		if (path.isEmpty()) {
			return ROOT_BUILDER;
		} else try {
			StepwiseDereferencerBuilder candidate = new StepwiseDereferencerBuilder(path, here());

			// If there's already an equivalent one filed under
			// the fully parameterized path, reuse that instead;
			// else, file our candidate under that path.
			Path fullyParameterizedPath = candidate.fullyParameterizedPath();
			return memoizedBuilders.computeIfAbsent(fullyParameterizedPath, x->candidate);
		} catch (InvalidTypeException e) {
			throw new TunneledCheckedException(e);
		}
	}

	/**
	 * Contains code generation logic representing the actions relating to a single segment within a Path.
	 */
	private interface Step {
		/**
		 * @return the {@link Type} of the object pointed to by the {@link Path} segment that this Step corresponds to
		 */
		Type targetType();

		/**
		 * @return the segment this step contributes to {@link StepwiseDereferencerBuilder#fullyParameterizedPath()}.
		 */
		String fullyParameterizedPathSegment();

		/**
		 * Initial stack: penultimateObject
		 * Final stack: targetObject
		 */
		void generate_get();

		/**
		 * Initial stack: penultimateObject newTargetObject
		 * Final stack: newPenultimateObject
		 */
		void generate_with();

		default Class<?> targetClass() {
			return rawClass(targetType());
		}
	}

	private interface DeletableStep extends Step {
		/**
		 * Initial stack: penultimateObject
		 * Final stack: newPenultimateObject
		 */
		void generate_without();
	}

	/**
	 * Implements {@link SkeletonDereferencerBuilder} by generating the {@link Dereferencer}
	 * methods from a list of {@link Step} objects (which sort of serve as the "intermediate
	 * representation" for this compiler).
	 */
	private final class StepwiseDereferencerBuilder extends SkeletonDereferencerBuilder {
		final List<Step> steps;

		//
		// Construction
		//

		public StepwiseDereferencerBuilder(Path path, StackTraceElement sourceFileOrigin) throws InvalidTypeException {
			super("DEREFERENCER", rawClass(sourceType).getClassLoader(), sourceFileOrigin);
			assert !path.isEmpty();
			steps = new ArrayList<>();
			Type currentType = sourceType;
			for (int i = 0; i < path.length(); i++) {
				Step step = newSegmentStep(currentType, path.segment(i), i);
				steps.add(step);
				currentType = step.targetType();
			}
		}

		private Step newSegmentStep(Type currentType, String segment, int segmentNum) throws InvalidTypeException {
			Class<?> currentClass = rawClass(currentType);
			if (Catalog.class.isAssignableFrom(currentClass)) {
				return new CatalogEntryStep(parameterType(currentType, Catalog.class, 0), segmentNum);
			} else if (Listing.class.isAssignableFrom(currentClass)) {
				return new ListingEntryStep(parameterType(currentType, Listing.class, 0), segmentNum);
			} else if (SideTable.class.isAssignableFrom(currentClass)) {
				Type keyType = parameterType(currentType, SideTable.class, 0);
				Type targetType = parameterType(currentType, SideTable.class, 1);
				return new SideTableEntryStep(keyType, targetType, segmentNum);
			} else if (StateTreeNode.class.isAssignableFrom(currentClass)) {
				if (isParameterSegment(segment)) {
					throw new InvalidTypeException("Invalid parameter location: expected a field of " + currentClass.getSimpleName());
				}
				Map<String, Method> getters = gettersForConstructorParameters(currentClass);

				// We currently support getters that are not constructor parameters. Useful for
				// "transient" fields that are actually computed dynamically, but they're also
				// a bit complicated and problematic. If we drop support, we should throw
				// InvalidTypeException here instead of adding the getter to the map. -pdoyle
				getters.put(segment, getterMethod(currentClass, segment));

				FieldStep fieldStep = new FieldStep(segment, getters, theOnlyConstructorFor(currentClass));
				Class<?> fieldClass = rawClass(fieldStep.targetType());
				if (Optional.class.isAssignableFrom(fieldClass)) {
					return new OptionalValueStep(parameterType(fieldStep.targetType(), Optional.class, 0), fieldStep);
				} else if (Phantom.class.isAssignableFrom(fieldClass)) {
					return new PhantomValueStep(parameterType(fieldStep.targetType(), Phantom.class, 0), segment);
				} else {
					return fieldStep;
				}
			} else {
				throw new InvalidTypeException("Can't reference contents of " + currentClass.getSimpleName());
			}
		}

		//
		// Skeleton "generate" methods
		//

		@Override
		protected void generate_get() {
			pushSourceObject(rawClass(sourceType));
			for (Step step: steps) {
				step.generate_get();
				castTo(step.targetClass());
			}
		}

		@Override
		protected void generate_with() {
			pushSegmentStack();
			pushNewValueObject(lastStep().targetClass());
			lastStep().generate_with();
			generateVineFoldingSequence();
		}

		@Override
		protected void generate_without() {
			if (lastStep() instanceof DeletableStep) {
				pushSegmentStack();
				((DeletableStep) lastStep()).generate_without();
				generateVineFoldingSequence();
			} else {
				pushSourceObject(rawClass(sourceType));
				pushReference();
				invoke(INVALID_WITHOUT);
			}
		}

		@Override public Type targetType() {
			return lastStep().targetType();
		}

		//
		// Helpers called by the "build" methods
		//

		private Step lastStep() {
			return steps.get(steps.size()-1);
		}

		/**
		 * Push values on the stack for each segment in order, except for the last segment
		 * (because that one usually needs special treatment).
		 *
		 * <p>
		 * Initial stack: (nothing)
		 * Final stack: sourceObject, segment_0, segment_1, ..., segment_n-2
		 */
		private void pushSegmentStack() {
			pushSourceObject(rawClass(sourceType));
			for (Step step: steps.subList(0, steps.size()-1)) {
				dup();
				step.generate_get();
				castTo(step.targetClass());
			}
		}

		/**
		 * Repeatedly call "with" until we work our way back out to a new root object.
		 * This is a bit like a "right fold" of the "with" functions.
		 *
		 * <p>
		 * Initial stack: sourceObject, segment_0, segment_1, ..., segment_n-3, newValue_n-2
		 * Final stack: newSourceObject
		 */
		private void generateVineFoldingSequence() {
			for (int i = steps.size()-2; i >= 0; i--) {
				Step step = steps.get(i);
				castTo(step.targetClass());
				step.generate_with();
			}
		}

		//
		// Fully-parameterized path calculation
		//

		@Override
		public Path fullyParameterizedPath() {
			String[] segments =
				steps.stream()
					.map(Step::fullyParameterizedPathSegment)
					.toArray(String[]::new);
			disambiguateDuplicateParameters(segments);
			return Path.of(segments);
		}

		private void disambiguateDuplicateParameters(String[] segments) {
			Set<String> parametersAlreadyUsed = new HashSet<>();
			for (int i = 0; i < segments.length; i++) {
				String initialSegment = segments[i];
				if (isParameterSegment(initialSegment)) {
					String segment = initialSegment;

					// As long as the segment is a dup, keep incrementing the suffix
					for (
						int suffix = 2;
						!parametersAlreadyUsed.add(segment);
						suffix++
					) {
						segment = initialSegment.substring(0, initialSegment.length()-1) + "_" + suffix + "-";
					}

					segments[i] = segment;
				}
			}
		}

		/**
		 * @return the conventional name to use for a parameter of the given type
		 */
		private String pathParameterName(Type targetType) {
			String name = rawClass(targetType).getSimpleName();
			return "-"
				+ name.substring(0,1).toLowerCase(ROOT)
				+ name.substring(1)
				+ "-";
		}


		//
		// Step implementations for the possible varieties of objects in the tree
		//

		@Value
		@Accessors(fluent = true)
		public class FieldStep implements Step {
			String name;
			Map<String, Method> gettersByName;
			Constructor<?> constructor;

			private Method getter() { return gettersByName.get(name); }

			@Override public Type targetType() { return getter().getGenericReturnType(); }

			@Override public String fullyParameterizedPathSegment() { return name; }

			@Override public void generate_get() { invoke(getter()); }

			@Override public void generate_with() {
				// This is too complex to do on the stack. Put what we need in local variables.
				LocalVariable newValue = cb.popToLocal();
				LocalVariable originalObject = cb.popToLocal();

				// Create a blank instance of the class
				cb.instantiate(constructor.getDeclaringClass());

				// Make a copy of the object reference to pass to the constructor;
				// the original will be the result we're returning.
				cb.dup();

				// Push constructor parameters and invoke
				for (Parameter parameter: constructor.getParameters()) {
					if (parameter.getName().equals(name)) {
						cb.pushLocal(newValue);
					} else {
						cb.pushLocal(originalObject);
						cb.invoke(gettersByName.get(parameter.getName()));
					}
				}
				cb.invoke(constructor);
			}
		}

		@Value
		@Accessors(fluent = true)
		public class CatalogEntryStep implements DeletableStep {
			Type targetType;
			int segmentNum;

			@Override public String fullyParameterizedPathSegment() { return pathParameterName(targetType); }

			@Override public void generate_get() { pushIdAt(segmentNum); pushReference(); invoke(CATALOG_GET); }
			@Override public void generate_with() { invoke(CATALOG_WITH); }
			@Override public void generate_without() { pushIdAt(segmentNum); invoke(CATALOG_WITHOUT); }
		}

		@Value
		@Accessors(fluent = true)
		public class ListingEntryStep implements DeletableStep {
			Type entryType;
			int segmentNum;

			/**
			 * A reference to a listing entry points at a {@link ListingEntry},
			 * not the Listing's entry type.
			 */
			@Override public Type targetType() {
				return ListingEntry.class;
			}

			@Override public String fullyParameterizedPathSegment() { return pathParameterName(entryType); }

			@Override public void generate_get() { pushIdAt(segmentNum); pushReference(); invoke(LISTING_GET); }
			@Override public void generate_with() { pushIdAt(segmentNum); swap(); invoke(LISTING_WITH); }
			@Override public void generate_without() { pushIdAt(segmentNum); invoke(LISTING_WITHOUT); }
		}

		@Value
		@Accessors(fluent = true)
		public class SideTableEntryStep implements DeletableStep {
			Type keyType;
			Type targetType;
			int segmentNum;

			@Override public String fullyParameterizedPathSegment() { return pathParameterName(keyType); }

			@Override public void generate_get() { pushIdAt(segmentNum); pushReference(); invoke(SIDE_TABLE_GET); }
			@Override public void generate_with() { pushIdAt(segmentNum); swap(); invoke(SIDE_TABLE_WITH); }
			@Override public void generate_without() { pushIdAt(segmentNum); invoke(SIDE_TABLE_WITHOUT); }
		}

		@Value
		@Accessors(fluent = true)
		public class OptionalValueStep implements DeletableStep {
			Type targetType;
			FieldStep fieldStep;

			@Override public String fullyParameterizedPathSegment() { return fieldStep.fullyParameterizedPathSegment(); }

			@Override public void generate_get() { fieldStep.generate_get(); pushReference(); invoke(OPTIONAL_OR_THROW); }
			@Override public void generate_with() { invoke(OPTIONAL_OF); fieldStep.generate_with(); }
			@Override public void generate_without() { invoke(OPTIONAL_EMPTY); fieldStep.generate_with(); }
		}

		@Value
		@Accessors(fluent = true)
		public class PhantomValueStep implements DeletableStep {
			Type targetType;
			String name;

			@Override public String fullyParameterizedPathSegment() { return name; }

			@Override public void generate_get() { pop(); pushReference(); invoke(THROW_NONEXISTENT_ENTRY); }
			@Override public void generate_with() { pop(); pop(); pushReference(); invoke(THROW_CANNOT_REPLACE_PHANTOM); }
			@Override public void generate_without() { /* No effect */ }
		}

	}

	/**
	 * Special-purpose {@link Dereferencer} for the empty path.
	 * This peels off a corner case so {@link StepwiseDereferencerBuilder} doesn't
	 * need to deal with it.
	 */
	private static final class RootDereferencer implements Dereferencer {
		@Override public Object get(Object source, Reference<?> ref) { return source; }
		@Override public Object with(Object source, Reference<?> ref, Object newValue) { return newValue; }
		@Override public Object without(Object source, Reference<?> ref) { return DereferencerRuntime.invalidWithout(source, ref); }
	}

	private final DereferencerBuilder ROOT_BUILDER = new DereferencerBuilder() {
		@Override public Type targetType() { return sourceType; }
		@Override public Path fullyParameterizedPath() { return Path.empty(); }
		@Override public Dereferencer buildInstance() { return new RootDereferencer(); }
	};

	//
	// Reflection performed once during initialization
	//

	static final Method CATALOG_GET, CATALOG_WITH, CATALOG_WITHOUT;
	static final Method LISTING_GET, LISTING_WITH, LISTING_WITHOUT;
	static final Method SIDE_TABLE_GET, SIDE_TABLE_WITH, SIDE_TABLE_WITHOUT;
	static final Method OPTIONAL_OF, OPTIONAL_OR_THROW, OPTIONAL_EMPTY;
	static final Method THROW_NONEXISTENT_ENTRY, THROW_CANNOT_REPLACE_PHANTOM;
	static final Method INVALID_WITHOUT;

	static {
		try {
			CATALOG_GET = DereferencerRuntime.class.getDeclaredMethod("catalogEntryOrThrow", Catalog.class, Identifier.class, Reference.class);
			CATALOG_WITH = Catalog.class.getDeclaredMethod("with", Entity.class);
			CATALOG_WITHOUT = Catalog.class.getDeclaredMethod("without", Identifier.class);
			LISTING_GET = DereferencerRuntime.class.getDeclaredMethod("listingEntryOrThrow", Listing.class, Identifier.class, Reference.class);
			LISTING_WITH = DereferencerRuntime.class.getDeclaredMethod("listingWith", Listing.class, Identifier.class, Object.class);
			LISTING_WITHOUT = Listing.class.getDeclaredMethod("withoutID", Identifier.class);
			SIDE_TABLE_GET = DereferencerRuntime.class.getDeclaredMethod("sideTableEntryOrThrow", SideTable.class, Identifier.class, Reference.class);
			SIDE_TABLE_WITH = SideTable.class.getDeclaredMethod("with", Identifier.class, Object.class);
			SIDE_TABLE_WITHOUT = SideTable.class.getDeclaredMethod("without", Identifier.class);
			OPTIONAL_OF = Optional.class.getDeclaredMethod("ofNullable", Object.class);
			OPTIONAL_OR_THROW = DereferencerRuntime.class.getDeclaredMethod("optionalOrThrow", Optional.class, Reference.class);
			OPTIONAL_EMPTY = Optional.class.getDeclaredMethod("empty");
			THROW_NONEXISTENT_ENTRY = DereferencerRuntime.class.getDeclaredMethod("throwNonexistentEntry", Reference.class);
			THROW_CANNOT_REPLACE_PHANTOM = DereferencerRuntime.class.getDeclaredMethod("throwCannotReplacePhantom", Reference.class);
			INVALID_WITHOUT = DereferencerRuntime.class.getDeclaredMethod("invalidWithout", Object.class, Reference.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}
}
