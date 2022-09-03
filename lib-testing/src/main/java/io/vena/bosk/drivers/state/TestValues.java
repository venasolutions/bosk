package io.vena.bosk.drivers.state;

import io.vena.bosk.ListValue;
import io.vena.bosk.MapValue;
import io.vena.bosk.StateTreeNode;
import java.time.temporal.ChronoUnit;
import lombok.Value;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;

import static java.time.temporal.ChronoUnit.FOREVER;

@Value
@Accessors(fluent = true)
@With
@FieldNameConstants
public class TestValues implements StateTreeNode {
	String string;
	ChronoUnit chronoUnit;
	ListValue<String> list;
	MapValue<String> map;

	public static TestValues blank() {
		return new TestValues("", FOREVER, ListValue.empty(), MapValue.empty());
	}
}
