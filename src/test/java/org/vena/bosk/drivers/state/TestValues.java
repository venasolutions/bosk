package org.vena.bosk.drivers.state;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.FieldNameConstants;
import org.vena.bosk.ListValue;
import org.vena.bosk.MapValue;
import org.vena.bosk.StateTreeNode;

@EqualsAndHashCode(callSuper = false)
@ToString
@Accessors(fluent = true)
@Getter
@With
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequiredArgsConstructor
@FieldNameConstants
public class TestValues implements StateTreeNode {
	String string;
	ListValue<String> list;
	MapValue<String> map;

	public static TestValues blank() {
		return new TestValues("", ListValue.empty(), MapValue.empty());
	}
}
