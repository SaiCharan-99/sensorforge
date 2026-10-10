package com.application.sensorforge.sdk.handover

/**
 * HandoverProof configuration supplied by the host app (tech spec Part F): the item list, the
 * signed-slip code and the minimum steps walked. The split between the partner and the customer
 * side is a vertical line in the camera image; [partnerOnLeft] says which side the partner is on.
 */
data class HandoverConfig(
    val items: List<String> = listOf("A", "B", "C"),
    val slipCode: String = "SLIP-1",
    val minSteps: Int = 0,
    val partnerOnLeft: Boolean = true,
    val splitX: Float = 0.5f,
    /** Dead band around the split: an item inside it is "in transit", not on either side. */
    val bandX: Float = 0.05f,
    /** An item must rest on the customer side, released by hand, this long to count as handed. */
    val settleS: Double = 0.8,
    /** An item unseen for longer than this ends its observability window. */
    val lostS: Double = 1.0,
) {
    init {
        require(items.isNotEmpty()) { "items list is empty" }
        require(items.distinct().size == items.size) { "duplicate item codes" }
        require(items.all { it.isNotBlank() && it.none { c -> c == '|' || c == ':' || c.isWhitespace() } }) { "item codes must be single tokens" }
        require(slipCode.isNotBlank()) { "slip code is empty" }
        require(minSteps >= 0) { "min steps must be >= 0" }
        require(splitX in 0.2f..0.8f) { "split must lie between 0.2 and 0.8" }
    }
}
