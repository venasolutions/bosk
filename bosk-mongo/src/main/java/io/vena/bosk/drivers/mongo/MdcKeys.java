package io.vena.bosk.drivers.mongo;

/**
 * Evolution note: we're going to want to get organized in how we generate MDC.
 * For now, it's all in bosk-mongo, so we can put these keys here.
 */
final class MdcKeys {
	static final String BOSK_NAME        = "bosk.name";
	static final String BOSK_INSTANCE_ID = "bosk.instanceID";
	static final String EVENT            = "bosk.MongoDriver.event";
	static final String TRANSACTION      = "bosk.MongoDriver.transaction";
}
