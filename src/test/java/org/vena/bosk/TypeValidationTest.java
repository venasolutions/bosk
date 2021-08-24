package org.vena.bosk;

import java.util.ArrayList;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.vena.bosk.AbstractBoskTest.AbstractReference;
import org.vena.bosk.annotations.DerivedRecord;
import org.vena.bosk.annotations.DeserializationPath;
import org.vena.bosk.annotations.Enclosing;
import org.vena.bosk.annotations.Self;
import org.vena.bosk.exceptions.InvalidTypeException;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.containsStringIgnoringCase;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
			FieldNameWithNonAsciiLetters.class,
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
			MappingWithInvalidKey.class,
			MappingWithInvalidValue.class,
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
	public static final class Primitives extends Entity {
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
	public static final class SimpleTypes extends Entity {
		Identifier id;
		String string;
		MyEnum myEnum;

		public enum MyEnum {
			LEFT, RIGHT
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class BoskyTypes extends Entity {
		Identifier id;
		Reference<SimpleTypes> ref;
		Optional<SimpleTypes> optional;
		Catalog<SimpleTypes> catalog;
		Listing<SimpleTypes> listing;
		Mapping<SimpleTypes, String> mappingToString;
		Mapping<SimpleTypes, SimpleTypes> mappingToEntity;
		ListValue<String> listValueOfStrings;
		ListValue<ValueStruct> listValueOfStructs;
		ListValueSubclass listValueSubclass;
		ReferenceSubclass referenceSubclass;
	}

	@Value @Accessors(fluent=true)
	public static class ValueStruct implements ConfigurationNode {
		String string;
		ListValue<String> innerList;
	}

	public static final class ListValueSubclass extends ListValue<String> {
		final String extraField;

		protected ListValueSubclass(String[] entries) {
			super(entries);
			this.extraField = "Hello";
		}
	}

	@RequiredArgsConstructor
	public static final class ReferenceSubclass extends AbstractReference<String> {
		final String validField;
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class AllowedFieldNames extends Entity {
		Identifier id;
		int justLetters;
		int someNumbers4U2C;
		int hereComesAnUnderscore_toldYouSo;
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class ImplicitReferences_onConstructorParameters extends Entity {
		Identifier id;
		Reference<ImplicitReferences_onConstructorParameters> selfRef;
		Reference<ConfigurationNode> selfSupertype;
		Reference<ImplicitReferences_onConstructorParameters> enclosingRef;

		public ImplicitReferences_onConstructorParameters(
			Identifier id,
			@Self Reference<ImplicitReferences_onConstructorParameters> selfRef,
			@Self Reference<ConfigurationNode> selfSupertype,
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
	public static final class ImplicitReferences_onFields extends Entity {
		Identifier id;
		@Self Reference<ImplicitReferences_onFields> selfRef;
		@Self Reference<ConfigurationNode> selfSupertype;
		@Enclosing Reference<ImplicitReferences_onFields> enclosingRef;
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class MissingConstructorArgument extends Entity {
		Identifier id;
		String field = "fieldValue";
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ExtraConstructor extends Entity {
		Identifier id;

		public ExtraConstructor(String extra) {
			this.id = Identifier.from("dummy");
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must have one constructor"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class ExtraConstructorArgument extends Entity {
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
	public static final class GetterHasParameter extends Entity {
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
	public static final class GetterReturnsWrongType extends Entity {
		Identifier id;
		String field;

		@Override public Identifier id() { return id; }
		public int field() { return 123; }

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Getter should return"));
		}
	}

	@Accessors(fluent=true) @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class GetterReturnsSupertype extends Entity {
		Identifier id;
		Integer field;

		@Override public Identifier id() { return id; }
		public Number field() { return field; }

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Getter should return"));
		}
	}

	@Accessors(fluent=true) @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class GetterReturnsSubtype extends Entity {
		Identifier id;
		Number field;

		@Override public Identifier id() { return id; }
		public Integer field() { return 123; }

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Getter should return"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE) @RequiredArgsConstructor
	public static class MutableField extends Entity {
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
	public static final class NestedError extends Entity {
		Identifier id;
		GetterReturnsWrongType field;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("NestedError.field"));
			assertThat(e.getMessage(), containsString("GetterReturnsWrongType.field"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ArrayField extends Entity {
		Identifier id;
		String[] strings;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ArrayField.strings"));
			assertThat(e.getMessage(), containsString("is not a"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ReferenceToInvalidType extends Entity {
		Identifier id;
		Reference<ArrayField> ref;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ReferenceToInvalidType.ref"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class CatalogOfInvalidType extends Entity {
		Identifier id;
		Catalog<ArrayField> catalog;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("CatalogOfInvalidType.catalog"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class ListingOfInvalidType extends Entity {
		Identifier id;
		Listing<ArrayField> listing;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListingOfInvalidType.listing"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class OptionalOfInvalidType extends Entity {
		Identifier id;
		Optional<ArrayField> optional;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("OptionalOfInvalidType.optional"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class MappingWithInvalidKey extends Entity {
		Identifier id;
		Mapping<ArrayField,String> mapping;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("MappingWithInvalidKey.mapping"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class MappingWithInvalidValue extends Entity {
		Identifier id;
		Mapping<SimpleTypes,ArrayField> mapping;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("MappingWithInvalidValue.mapping"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class FieldNameWithDollarSign extends Entity {
		Identifier id;
		int weird$name;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("FieldNameWithDollarSign.weird$name"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static final class FieldNameWithNonAsciiLetters extends Entity {
		Identifier id;
		int trèsCassé;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("FieldNameWithNonAsciiLetters.trèsCassé"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	public static final class EnclosingNonReference extends Entity {
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
	public static final class EnclosingReferenceToString extends Entity {
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
	public static final class EnclosingReferenceToCatalog extends Entity {
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
	public static final class EnclosingReferenceToOptional extends Entity {
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
	public static final class SelfNonReference extends Entity {
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
	public static final class SelfWrongType extends Entity {
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
	public static class SelfSubtype extends Entity {
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
	public static final class HasDeserializationPath extends Entity {
		Identifier id;
		@DeserializationPath("")
		SimpleTypes badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("HasDeserializationPath.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfIdentifier extends Entity {
		Identifier id;
		ListValue<Identifier> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfIdentifier.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfReference extends Entity {
		Identifier id;
		ListValue<Reference<String>> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfReference.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfEntity extends Entity {
		Identifier id;
		ListValue<SimpleTypes> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfEntity.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueOfOptional extends Entity {
		Identifier id;
		ListValue<Optional<SimpleTypes>> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfOptional.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueInvalidSubclass extends Entity {
		Identifier id;
		InvalidSubclass badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueInvalidSubclass.badField"));
		}

		public static class InvalidSubclass extends ListValue<Identifier> {
			protected InvalidSubclass(Identifier[] entries) {
				super(entries);
			}
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueMutableSubclass extends Entity {
		Identifier id;
		MutableSubclass badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueMutableSubclass.badField"));
			assertThat(e.getMessage(), containsString("MutableSubclass.mutableField"));
		}

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
	public static final class ListValueOfInvalidType extends Entity {
		Identifier id;
		ListValue<ArrayList<Object>> badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfInvalidType.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ListValueSubclassWithMutableField extends Entity {
		Identifier id;
		Subclass badField;

		@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE)
		public static final class Subclass extends ListValue<String> {
			int mutableField;

			protected Subclass(String[] entries) {
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
	public static final class ListValueSubclassWithTwoConstructors extends Entity {
		Identifier id;
		Subclass badField;

		@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE)
		public static final class Subclass extends ListValue<String> {
			protected Subclass(String[] entries) {
				super(entries);
			}
			protected Subclass() {
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
	public static final class ListValueSubclassWithWrongConstructor extends Entity {
		Identifier id;
		Subclass badField;

		@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE)
		public static final class Subclass extends ListValue<String> {
			protected Subclass() {
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
	public static final class DerivedRecordType extends Entity {
		Identifier id;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString(DerivedRecord.class.getSimpleName()));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class DerivedRecordField extends Entity {
		Identifier id;
		DerivedRecordType badField;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString(DerivedRecord.class.getSimpleName()));
			assertThat(e.getMessage(), containsString("DerivedRecordField.badField"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ReferenceToReference extends Entity {
		Identifier id;
		Reference<Reference<String>> ref;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ReferenceToReference.ref"));
		}
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ReferenceWithMutableField extends Entity {
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
	 * Catches a case of overexuberant memoization we were doing, where we'd
	 * only validate each class once.
	 *
	 * @author Patrick Doyle
	 */
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static final class ValidThenInvalidOfTheSameClass extends Entity {
		Identifier id;
		ListValue<String> good;
		ListValue<Identifier> bad;

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ValidThenInvalidOfTheSameClass.bad"));
		}
	}

}
