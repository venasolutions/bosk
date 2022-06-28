package org.vena.bosk;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.Test;
import org.vena.bosk.Bosk.ReadContext;
import org.vena.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BsonPluginTest {

	@Test
	void sideTableOfSideTables() {
		BsonPlugin bp = new BsonPlugin();
		Bosk<Root> bosk = new Bosk<Root>("Test bosk", Root.class, this::defaultRoot, Bosk::simpleDriver);
		CodecRegistry registry = CodecRegistries.fromProviders(bp.codecProviderFor(bosk), new ValueCodecProvider());
		Codec<Root> codec = registry.get(Root.class);
		try (ReadContext context = bosk.readContext()) {
			BsonDocument document = new BsonDocument();
			Root original = bosk.rootReference().value();
			codec.encode(new BsonDocumentWriter(document), original, EncoderContext.builder().build());
			Root decoded = codec.decode(new BsonDocumentReader(document), DecoderContext.builder().build());
			assertEquals(original, decoded);
		}
	}

	private Root defaultRoot(Bosk<Root> bosk) throws InvalidTypeException {
		CatalogReference<Item> catalogRef = bosk.catalogReference(Item.class, Path.just(Root.Fields.items));
		return new Root(Identifier.from("root"), Catalog.empty(), SideTable.empty(catalogRef));
	}

	@Value @Accessors(fluent = true) @FieldNameConstants
	@EqualsAndHashCode(callSuper = false)
	public static class Root implements Entity {
		Identifier id;
		Catalog<Item> items;
		SideTable<Item, SideTable<Item, String>> nestedSideTable;
	}

	@Value @Accessors(fluent = true) @FieldNameConstants
	@EqualsAndHashCode(callSuper = false)
	public static class Item implements Entity {
		Identifier id;
	}

}
