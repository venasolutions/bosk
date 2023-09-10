package io.vena.bosk.drivers.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import java.util.AbstractList;
import java.util.List;
import lombok.Value;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.lang.System.identityHashCode;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DemultiplexerTest {
	Demultiplexer dem;

	@BeforeEach
	void init() {
		dem = new Demultiplexer();
	}

	@Test
	void test() {
		BsonDocument lsid1 = new BsonDocument("_id", new BsonInt64(1));
		BsonDocument lsid2 = new BsonDocument("_id", new BsonInt64(2));
		List<ChangeStreamDocument<Document>> list1 = asList(
			event(lsid1, 3),
			event(lsid1, 3)
		);
		List<ChangeStreamDocument<Document>> list2 = asList(
			event(lsid1, 4),
			event(lsid1, 4)
		);
		List<ChangeStreamDocument<Document>> list3 = asList(
			event(lsid2, 3),
			event(lsid2, 3)
		);

		for (int i = 0; i < list1.size(); i++) {
			dem.add(list1.get(i));
			dem.add(list2.get(i));
			dem.add(list3.get(i));
		}

		assertSameElements(list1, dem.pop(list1.get(0)));
		assertSameElements(list2, dem.pop(list2.get(0)));
		assertSameElements(list3, dem.pop(list3.get(0)));
	}

	private void assertSameElements(List<ChangeStreamDocument<Document>> expected, List<ChangeStreamDocument<Document>> actual) {
		assertEquals(new IdentityList<>(expected), actual);
	}

	/**
	 * Wrapper {@link List} whose {@link #hashCode} and {@link #equals} compare
	 * list elements by identity rather than value.
	 */
	@Value
	private static class IdentityList<T> extends AbstractList<T> {
		List<T> contents;

		@Override public T get(int index) { return contents.get(index); }
		@Override public int size() { return contents.size(); }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null) return false;
			if (!super.equals(o)) return false;

			List<?> that = (List<?>) o;

			if (contents.size() != that.size()) {
				return false;
			}
			for (int i = 0; i < contents.size(); i++) {
				if (contents.get(i) != that.get(i)) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			int result = super.hashCode();
			result = 31 * result + (contents != null ? identityHashCode(contents) : 0);
			return result;
		}

		@Override
		public String toString() {
			return contents.toString();
		}
	}

	static ChangeStreamDocument<Document> event(BsonDocument lsid, long txnNumber) {
		BsonDocument ns = new BsonDocument()
			.append("coll", new BsonString("collection"))
			.append("db", new BsonString("database"));
		return new ChangeStreamDocument<>(
			OperationType.OTHER,
			new BsonDocument("_id", new BsonString("resumeToken")),
			ns, ns,
			null, null, null, null,
			new BsonInt64(txnNumber),
			lsid
		);
	}
}
