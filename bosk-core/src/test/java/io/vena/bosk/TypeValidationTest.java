package io.vena.bosk;

import io.vena.bosk.AbstractBoskTest.AbstractReference;
import io.vena.bosk.annotations.DerivedRecord;
import io.vena.bosk.annotations.DeserializationPath;
import io.vena.bosk.annotations.Enclosing;
import io.vena.bosk.annotations.Self;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.ArrayList;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.containsStringIgnoringCase;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("unused") // The classes here are for type analysis, not to be instantiated and used
class TypeValidationTest {

	@ParameterizedTest
	@ValueSource(classes = {
			Primitives.class,
			SimpleTypes.class,
			BoskyTypes.class,
			AllowedFieldNames.class,
			ImplicitReferences_onConstructorParameters.class,
			ImplicitReferences_onFields.class,
			})
	void testValidRootClasses(Class<?> rootClass) throws InvalidTypeException {
		TypeValidation.validateType(rootClass);
	}

	@ParameterizedTest
	@ValueSource(classes = {
			String.class,
			//MissingConstructorArgument.class, // TODO: Currently doesn't work because Bosk is constructor-driven. Figure out what's the system of record here
			ArrayField.class,
			CatalogOfInvalidType.class,
			DerivedRecordField.class,
			DerivedRecordType.class,
			EnclosingNonReference.class,
			EnclosingReferenceToCatalog.class,
			EnclosingReferenceToOptional.class,
			EnclosingReferenceToString.class,
			ExtraConstructor.class,
			ExtraConstructorArgument.class,
			FieldNameWithDollarSign.class,
			GetterHasParameter.class,
			GetterReturnsSubtype.class,
			GetterReturnsSupertype.class,
			GetterReturnsWrongType.class,
			HasDeserializationPath.class,
			ListingOfInvalidType.class,
			ListValueInvalidSubclass.class,
			ListValueMutableSubclass.class,
			ListValueOfEntity.class,
			ListValueOfIdentifier.class,
			ListValueOfInvalidType.class,
			ListValueOfOptional.class,
			ListValueOfReference.class,
			ListValueSubclassWithMutableField.class,
			ListValueSubclassWithTwoConstructors.class,
			ListValueSubclassWithWrongConstructor.class,
			ReferenceToReference.class,
			ReferenceWithMutableField.class,
			SelfNonReference.class,
			SelfWrongType.class,
			SelfSubtype.class,
			SideTableWithInvalidKey.class,
			SideTableWithInvalidValue.class,
			MutableField.class,
			MutableInheritedField.class,
			NestedError.class,
			OptionalOfInvalidType.class,
			ReferenceToInvalidType.class,
			ValidThenInvalidOfTheSameClass.class,
			})
	void testInvalidRootClasses(Class<?> rootClass) throws Exception {
		try {
			TypeValidation.validateType(rootClass);
		} catch (InvalidTypeException e) {
			try {
				rootClass.getDeclaredMethod("testException", InvalidTypeException.class).invoke(null, e);
			} catch (NoSuchMethodException ignore) {
				// no prob
			}
			// All is well
			return;
		}
		fail("Expected exception was not thrown for " + rootClass.getSimpleName());
	}

	@Test
	void testIsBetween() {
		// <sigh> Java has no standard function for this, so to get full coverage, we need to test ours.
		assertFalse(TypeValidation.isBetween('b', 'e', 'a'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'b'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'c'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'd'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'e'));
		assertFalse(TypeValidation.isBetween('b', 'e', 'f'));
	}

	//
	// OK, here come the classes...
	//

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class Primitives implements Entity {
		Identifier id;
		boolean booleanPrimitive;
		byte bytePrimitive;
		short shortPrimitive;
		int intPrimitive;
		long longPrimitive;
		float floatPrimitive;
		double doublePrimitive;
		Boolean booleanObject;
		Byte byteObject;
		Short shortObject;
		Integer intObject;
		Long longObject;
		Float floatObject;
		Double doubleObject;
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class SimpleTypes implements Entity {
		Identifier id;
		String string;
		MyEnum myEnum;

		public enum MyEnum {
			LEFT, RIGHT
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class BoskyTypes implements Entity {
		Identifier id;
		Reference<SimpleTypes> ref;
		Optional<SimpleTypes> optional;
		Catalog<SimpleTypes> catalog;
		Listing<SimpleTypes> listing;
		SideTable<SimpleTypes, String> sideTableToString;
		SideTable<SimpleTypes, SimpleTypes> sideTableToEntity;
		ListValue<String> listValueOfStrings;
		ListValue<ValueStruct> listValueOfStructs;
		ListValueSubclass listValueSubclass;
		ReferenceSubclass referenceSubclass;
	}

	@Value @Accessors(fluent=true)
	public static class ValueStruct implements StateTreeNode {
		String string;
		ListValue<String> innerList;
	}

	@EqualsAndHashCode(callSuper = true)
	public static final class ListValueSubclass extends ListValue<String> {
		final String extraField;

		ListValueSubclass(String[] entries) {
			super(entries);
			this.extraField = "Hello";
		}
	}

	@RequiredArgsConstructor
	public static final class ReferenceSubclass extends AbstractReference<String> {
		final String validField;
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class AllowedFieldNames implements Entity {
		Identifier id;
		int justLetters;
		int someNumbers4U2C;
		int hereComesAnUnderscore_toldYouSo;
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class ImplicitReferences_onConstructorParameters implements Entity {
		Identifier id;
		Reference<ImplicitReferences_onConstructorParameters> selfRef;
		Reference<StateTreeNode> selfSupertype;
		Reference<ImplicitReferences_onConstructorParameters> enclosingRef;

		public ImplicitReferences_onConstructorParameters(
			Identifier id,
			@Self Reference<ImplicitReferences_onConstructorParameters> selfRef,
			@Self Reference<StateTreeNode> selfSupertype,
			@Enclosing Reference<ImplicitReferences_onConstructorParameters> enclosingRef
		) {
			this.id = id;
			this.selfRef = selfRef;
			this.selfSupertype = selfSupertype;
			this.enclosingRef = enclosingRef;
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ImplicitReferences_onFields implements Entity {
		Identifier id;
		@Self Reference<ImplicitReferences_onFields> selfRef;
		@Self Reference<StateTreeNode> selfSupertype;
		@Enclosing Reference<ImplicitReferences_onFields> enclosingRef;
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class MissingConstructorArgument implements Entity {
		Identifier id;
		String field = "fieldValue";
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ExtraConstructor implements Entity {
		Identifier id;

		public ExtraConstructor(String extra) {
			this.id = Identifier.from("dummy");
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must have one constructor"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class ExtraConstructorArgument implements Entity {
		Identifier id;

		public ExtraConstructorArgument(Identifier id, String extra) {
			this.id = id;
		}

		ExtraConstructorArgument withId(Identifier id) {
			return new ExtraConstructorArgument(id, "dummy");
		}

		public static void testException(InvalidTypeException e) {
			// Actually detected as a missing getter
			assertThat(e.getMessage(), containsString("No method"));
		}
	}

	@Accessors(fluent=true) @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class GetterHasParameter implements Entity {
		Identifier id;
		String field;

		@Override public Identifier id() { return id; }
		public String field(int wonkyParameter) { return field; }

		public static void testException(InvalidTypeException e) {
			// Actually detected as a missing getter
			assertThat(e.getMessage(), containsString("No method"));
		}
	}

	@Accessors(fluent=true) @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class GetterReturnsWrongType implements Entity {
		Identifier id;
		String field;

		@Override public Identifier id() { return id; }
		public int field() { return 123; }

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Getter should return"));
		}
	}

	@Accessors(fluent=true) @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class GetterReturnsSupertype implements Entity {
		Identifier id;
		Integer field;

		@Override public Identifier id() { return id; }
		public Number field() { return field; }

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Getter should return"));
		}
	}

	@Accessors(fluent=true) @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class GetterReturnsSubtype implements Entity {
		Identifier id;
		Number field;

		@Override public Identifier id() { return id; }
		public Integer field() { return 123; }

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Getter should return"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE) @RequiredArgsConstructor
	public static class MutableField implements Entity {
		Identifier id;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("not final"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE) @RequiredArgsConstructor
	public static final class MutableInheritedField extends MutableField {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("not final"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class NestedError implements Entity {
		Identifier id;
		GetterReturnsWrongType field;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("NestedError.field"));
			assertThat(e.getMessage(), containsString("GetterReturnsWrongType.field"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ArrayField implements Entity {
		Identifier id;
		String[] strings;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ArrayField.strings"));
			assertThat(e.getMessage(), containsString("is not a"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ReferenceToInvalidType implements Entity {
		Identifier id;
		Reference<ArrayField> ref;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ReferenceToInvalidType.ref"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class CatalogOfInvalidType implements Entity {
		Identifier id;
		Catalog<ArrayField> catalog;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("CatalogOfInvalidType.catalog"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ListingOfInvalidType implements Entity {
		Identifier id;
		Listing<ArrayField> listing;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListingOfInvalidType.listing"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class OptionalOfInvalidType implements Entity {
		Identifier id;
		Optional<ArrayField> optional;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("OptionalOfInvalidType.optional"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class SideTableWithInvalidKey implements Entity {
		Identifier id;
		SideTable<ArrayField,String> sideTable;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SideTableWithInvalidKey.sideTable"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class SideTableWithInvalidValue implements Entity {
		Identifier id;
		SideTable<SimpleTypes,ArrayField> sideTable;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SideTableWithInvalidValue.sideTable"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class FieldNameWithDollarSign implements Entity {
		Identifier id;
		int weird$name;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("FieldNameWithDollarSign.weird$name"));
		}
	}

	/*
	 * According to JLS 3.1, Java identifiers comprise only ASCII characters.
	 * https://docs.oracle.com/javase/specs/jls/se14/html/jls-3.html#jls-3.1
	 *
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class FieldNameWithNonAsciiLetters implements Entity {
		Identifier id;
		int trèsCassé;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("FieldNameWithNonAsciiLetters.trèsCassé"));
		}
	}
	 */

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class EnclosingNonReference implements Entity {
		Identifier id;
		String enclosingString;

		public EnclosingNonReference(Identifier id, @Enclosing String enclosingString) {
			this.id = id;
			this.enclosingString = enclosingString;
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingNonReference.enclosingString"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class EnclosingReferenceToString implements Entity {
		Identifier id;
		Reference<String> enclosingStringReference;

		public EnclosingReferenceToString(Identifier id, @Enclosing Reference<String> enclosingStringReference) {
			this.id = id;
			this.enclosingStringReference = enclosingStringReference;
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingReferenceToString.enclosingStringReference"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class EnclosingReferenceToCatalog implements Entity {
		Identifier id;
		Reference<Catalog<SimpleTypes>> enclosingCatalogReference;

		public EnclosingReferenceToCatalog(Identifier id, @Enclosing Reference<Catalog<SimpleTypes>> enclosingCatalogReference) {
			this.id = id;
			this.enclosingCatalogReference = enclosingCatalogReference;
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingReferenceToCatalog.enclosingCatalogReference"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class EnclosingReferenceToOptional implements Entity {
		Identifier id;
		Reference<Optional<SimpleTypes>> enclosingOptionalReference;

		public EnclosingReferenceToOptional(Identifier id, @Enclosing Reference<Optional<SimpleTypes>> enclosingOptionalReference) {
			this.id = id;
			this.enclosingOptionalReference = enclosingOptionalReference;
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingReferenceToOptional.enclosingOptionalReference"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class SelfNonReference implements Entity {
		Identifier id;
		String str;

		public SelfNonReference(Identifier id, @Enclosing String str) {
			this.id = id;
			this.str = str;
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SelfNonReference.str"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class SelfWrongType implements Entity {
		Identifier id;
		Reference<SimpleTypes> ref;

		public SelfWrongType(Identifier id, @Self Reference<SimpleTypes> ref) {
			this.id = id;
			this.ref = ref;
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SelfWrongType.ref"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static class SelfSubtype implements Entity {
		Identifier id;
		Reference<TheSubtype> ref;

		public SelfSubtype(Identifier id, @Self Reference<TheSubtype> ref) {
			this.id = id;
			this.ref = ref;
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SelfSubtype.ref"));
		}

		public static class TheSubtype extends SelfSubtype {
			public TheSubtype(Identifier id, Reference<TheSubtype> ref) {
				super(id, ref);
			}
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class HasDeserializationPath implements Entity {
		Identifier id;
		@DeserializationPath("")
		SimpleTypes badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("HasDeserializationPath.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfIdentifier implements Entity {
		Identifier id;
		ListValue<Identifier> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfIdentifier.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfReference implements Entity {
		Identifier id;
		ListValue<Reference<String>> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfReference.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfEntity implements Entity {
		Identifier id;
		ListValue<SimpleTypes> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfEntity.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfOptional implements Entity {
		Identifier id;
		ListValue<Optional<SimpleTypes>> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfOptional.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueInvalidSubclass implements Entity {
		Identifier id;
		InvalidSubclass badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueInvalidSubclass.badField"));
		}

		@EqualsAndHashCode(callSuper = true)
		public static class InvalidSubclass extends ListValue<Identifier> {
			protected InvalidSubclass(Identifier[] entries) {
				super(entries);
			}
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueMutableSubclass implements Entity {
		Identifier id;
		MutableSubclass badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueMutableSubclass.badField"));
			assertThat(e.getMessage(), containsString("MutableSubclass.mutableField"));
		}

		@EqualsAndHashCode(callSuper = true)
		public static class MutableSubclass extends ListValue<String> {
			String mutableField;

			protected MutableSubclass(String[] entries, String mutableField) {
				super(entries);
				this.mutableField = mutableField;
			}
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfInvalidType implements Entity {
		Identifier id;
		ListValue<ArrayList<Object>> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfInvalidType.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueSubclassWithMutableField implements Entity {
		Identifier id;
		Subclass badField;

		@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE)
		@EqualsAndHashCode(callSuper = true)
		public static final class Subclass extends ListValue<String> {
			int mutableField;

			Subclass(String[] entries) {
				super(entries);
			}
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueSubclassWithMutableField.badField"));
			assertThat(e.getMessage(), containsString("Subclass.mutableField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueSubclassWithTwoConstructors implements Entity {
		Identifier id;
		Subclass badField;

		@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE)
		@EqualsAndHashCode(callSuper = true)
		public static final class Subclass extends ListValue<String> {
			Subclass(String[] entries) {
				super(entries);
			}
			Subclass() {
				super(new String[]{"Hello"});
			}
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueSubclassWithTwoConstructors.badField"));
			assertThat(e.getMessage(), containsStringIgnoringCase("ambiguous"));
			assertThat(e.getMessage(), containsStringIgnoringCase("constructor"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueSubclassWithWrongConstructor implements Entity {
		Identifier id;
		Subclass badField;

		@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE)
		@EqualsAndHashCode(callSuper = true)
		public static final class Subclass extends ListValue<String> {
			Subclass() {
				super(new String[]{"Hello"});
			}
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueSubclassWithWrongConstructor.badField"));
			assertThat(e.getMessage(), containsStringIgnoringCase("constructor"));
			assertThat(e.getMessage(), not(containsStringIgnoringCase("ambiguous")));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	@DerivedRecord
	public static final class DerivedRecordType implements Entity {
		Identifier id;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString(DerivedRecord.class.getSimpleName()));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class DerivedRecordField implements Entity {
		Identifier id;
		DerivedRecordType badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString(DerivedRecord.class.getSimpleName()));
			assertThat(e.getMessage(), containsString("DerivedRecordField.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ReferenceToReference implements Entity {
		Identifier id;
		Reference<Reference<String>> ref;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ReferenceToReference.ref"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ReferenceWithMutableField implements Entity {
		Identifier id;
		BadReferenceSubclass ref;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ReferenceWithMutableField.ref"));
			assertThat(e.getMessage(), containsString("BadReferenceSubclass.mutableField"));
		}
	}

	public static final class BadReferenceSubclass extends AbstractReference<String> {
		String mutableField;
	}

	/**
	 * Catches a case of over-exuberant memoization we were doing, where we'd
	 * only validate each class once.
	 *
	 * @author Patrick Doyle
	 */
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ValidThenInvalidOfTheSameClass implements Entity {
		Identifier id;
		ListValue<String> good;
		ListValue<Identifier> bad;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ValidThenInvalidOfTheSameClass.bad"));
		}
	}

}
