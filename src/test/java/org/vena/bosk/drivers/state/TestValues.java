package org.vena.bosk.drivers.state;

import lombok.Value;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;
import org.vena.bosk.ListValue;
import org.vena.bosk.MapValue;
import org.vena.bosk.StateTreeNode;

@Value
@Accessors(fluent = true)
@With
@FieldNameConstants
public class TestValues implements StateTreeNode {
	String string;
	ListValue<String> list;
	MapValue<String> map;

	public static TestValues blank() {
		return new TestValues("", ListValue.empty(), MapValue.empty());
	}
}
