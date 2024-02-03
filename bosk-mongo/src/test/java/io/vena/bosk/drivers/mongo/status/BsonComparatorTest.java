package io.vena.bosk.drivers.mongo.status;

import java.util.ArrayList;
import java.util.List;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.drivers.mongo.status.BsonComparator.MAX_DIFFERENCES;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BsonComparatorTest {
	BsonComparator bsonComparator;

	@BeforeEach
	void initialize() {
		bsonComparator = new BsonComparator();
	}

	@Test
	void primitiveCombos_work() {
		checkPrimitiveCombos(
			new BsonString("hello"),
			new BsonBoolean(false),
			new BsonInt32(123),
			new BsonInt64(123456)
		);
	}

	private void checkPrimitiveCombos(BsonValue... values) {
		for (var left: values) {
			for (var right: values) {
				Difference expected;
				if (left.equals(right)) {
					expected = new NoDifference();
				} else {
					expected = new PrimitiveDifference("");
				}
				assertEquals(expected, bsonComparator.difference(left, right));
			}
		}
	}

	@Test
	void oneDifferentField_works() {
		var common = new BsonDocument("same1", new BsonInt32(1));
		var left = common.clone().append("different1", new BsonInt32(2));
		var right = common.clone().append("different1", new BsonInt32(3));
		assertEquals(new PrimitiveDifference("different1"),
			bsonComparator.difference(left, right));
	}

	@Test
	void twoDifferentFields_compound() {
		var left = new BsonDocument("field1", new BsonInt32(1))
			.append("field2", new BsonInt32(2));
		var right = new BsonDocument("field1", new BsonInt32(3))
			.append("field2", new BsonInt32(4));
		assertEquals(new MultipleDifferences("", List.of(
			new PrimitiveDifference("field1"),
			new PrimitiveDifference("field2")
		)), bsonComparator.difference(left, right));
	}

	@Test
	void manyDifferentFields_truncated() {
		var left = new BsonDocument();
		var right = new BsonDocument();
		var differences = new ArrayList<Difference>();
		for (int i = 1; i <= 1 + MAX_DIFFERENCES; i++) {
			left.append("field" + i, new BsonInt32(i));
			right.append("field" + i, new BsonInt32(-i));
			differences.add(new PrimitiveDifference("field" + i));
		}
		var expectedDifference = new MultipleDifferences("", differences.subList(0, MAX_DIFFERENCES));

		assertEquals(expectedDifference, bsonComparator.difference(left, right));
	}

	@Test
	void nestedDifferentField_correctlyPrefixed() {
		var left = new BsonDocument("field1",
			new BsonDocument("field2",
			new BsonDocument("field3",
			new BsonInt32(1))));
		var right = new BsonDocument("field1",
			new BsonDocument("field2",
			new BsonDocument("field3",
			new BsonInt32(2))));
		assertEquals(new PrimitiveDifference("field1.field2.field3"),
			bsonComparator.difference(left, right));
	}
}