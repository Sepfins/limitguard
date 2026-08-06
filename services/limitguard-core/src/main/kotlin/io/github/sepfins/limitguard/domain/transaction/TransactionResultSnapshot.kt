package io.github.sepfins.limitguard.domain.transaction

@JvmInline
value class TransactionResultSnapshot(val value: Map<String, Any?>) {
    init {
        val snapshotVersion = value["snapshotVersion"] as? Number
        require(snapshotVersion != null && snapshotVersion.toInt() > 0) {
            "TransactionResultSnapshot must contain a positive snapshotVersion"
        }
    }
}
