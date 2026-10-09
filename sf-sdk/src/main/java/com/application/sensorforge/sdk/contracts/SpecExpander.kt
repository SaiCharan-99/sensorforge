package com.application.sensorforge.sdk.contracts

/**
 * Expands `"for_each": "<param>"` steps with `{item}` placeholders before evaluation
 * (tech spec §6). Constraint step refs containing `{item}` expand per item;
 * ANY_ORDER flattens to every expanded id.
 */
object SpecExpander {
    private const val ITEM = "{item}"

    fun expand(spec: ProcedureSpec, params: Map<String, List<String>>): ProcedureSpec {
        val missing = spec.parameters.filter { (name, def) -> !def.optional && name !in params }.keys
        if (missing.isNotEmpty()) {
            throw ContractException(missing.map { ContractError("$.parameters.$it", "required parameter '$it' not supplied") })
        }
        if (spec.steps.none { it.forEach != null }) return spec

        // Items keyed by the step id template they came from, so constraints can follow them.
        val itemsFor = mutableMapOf<String, List<String>>()
        val steps = spec.steps.flatMap { s ->
            val param = s.forEach ?: return@flatMap listOf(s)
            val items = params[param].orEmpty()
            itemsFor[s.id] = items
            items.map { item ->
                s.copy(
                    id = s.id.sub(item),
                    entity = s.entity?.sub(item),
                    container = s.container?.sub(item),
                    zone = s.zone?.sub(item),
                    state = s.state?.sub(item),
                    observeTarget = s.observeTarget.sub(item),
                    forEach = null,
                )
            }
        }

        fun itemsOf(ref: String) = itemsFor[ref] ?: emptyList()
        val constraints = spec.constraints.flatMap { c ->
            when (c) {
                is Before -> pairwise(c.a, c.b, ::itemsOf).map { (a, b) -> c.copy(a = a, b = b) }
                is SameIdentity -> pairwise(c.a, c.b, ::itemsOf).map { (a, b) -> c.copy(a = a, b = b) }
                is AnyOrder -> listOf(c.copy(steps = c.steps.flatMap { r -> if (ITEM in r) itemsOf(r).map { r.sub(it) } else listOf(r) }))
            }
        }
        return spec.copy(steps = steps, constraints = constraints)
    }

    private fun pairwise(a: String, b: String, itemsOf: (String) -> List<String>): List<Pair<String, String>> = when {
        ITEM in a && ITEM in b -> itemsOf(a).map { a.sub(it) to b.sub(it) }
        ITEM in a -> itemsOf(a).map { a.sub(it) to b }
        ITEM in b -> itemsOf(b).map { a to b.sub(it) }
        else -> listOf(a to b)
    }

    private fun String.sub(item: String) = replace(ITEM, item)
}
