package io.vena.bosk.drivers.mongo.status;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.WRAPPER_OBJECT;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.MINIMAL_CLASS;

// We'd rather use SIMPLE_CLASS but that is a pretty new feature
// and some users might not have that available in older Jackson versions
@JsonTypeInfo(use = MINIMAL_CLASS, include = WRAPPER_OBJECT)
sealed public interface Difference permits
	NoDifference,
	SomeDifference
{
	Difference withPrefix(String prefix);

	static String prefixed(String prefix, String suffix) {
		if (suffix.isEmpty()) {
			return prefix;
		} else {
			return prefix + "." + suffix;
		}
	}
}
