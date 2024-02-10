package io.vena.bosk.drivers.mongo.status;

import java.util.ArrayList;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonValue;

public class BsonComparator {

	public Difference difference(BsonValue expected, BsonValue actual) {
		if (expected.getBsonType() != actual.getBsonType()) {
			return new PrimitiveDifference("");
		}

		// Now we know they have the same bson type

		if (expected instanceof BsonDocument expectedDoc && actual instanceof BsonDocument actualDoc) {
			var differences = new ArrayList<Difference>();
			for (String expectedKey: expectedDoc.keySet()) {
				var expectedValue = expectedDoc.get(expectedKey);
				var actualValue = actualDoc.get(expectedKey);
				if (actualValue == null) {
					differences.add(new NodeMissing(expectedKey));
				} else {
					Difference fieldDifference = difference(expectedValue, actualValue);
					if (!(fieldDifference instanceof NoDifference)) {
						differences.add(fieldDifference.withPrefix(expectedKey));
					}
				}
				if (differences.size() >= MAX_DIFFERENCES) {
					break;
				}
			};
			for (Map.Entry<String, BsonValue> entry : actualDoc.entrySet()) {
				var expectedValue = expectedDoc.get(entry.getKey());
				if (expectedValue == null) {
					differences.add(new UnexpectedNode(entry.getKey()));
				}
				if (differences.size() >= MAX_DIFFERENCES) {
					break;
				}
			}
			return switch (differences.size()) {
				case 0 -> new NoDifference();
				case 1 -> differences.get(0);
				default -> {
					// To prevent exponential fan-out, permit only max one compound inside another compound
					int numCompounds = 0;
					var iter = differences.iterator();
					while (iter.hasNext()) {
						var difference = iter.next();
						if (difference instanceof MultipleDifferences) {
							if (numCompounds == 0) {
								numCompounds++;
							} else {
								iter.remove();
							}
						}
					}
					yield new MultipleDifferences("", differences);
				}
			};
		} else if (expected.equals(actual)) {
			return new NoDifference();
		} else {
			return new PrimitiveDifference("");
		}
	}

	public static final int MAX_DIFFERENCES = 4;

}
