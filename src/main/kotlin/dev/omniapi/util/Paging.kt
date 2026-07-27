package dev.omniapi.util

import dev.omniapi.model.Page
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context

object Paging {
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 500

    fun parameters(ctx: Context): Pair<Int, Int> {
        val offset = parseInt(ctx.queryParam("offset"), "offset", 0)
        val limit = parseInt(ctx.queryParam("limit"), "limit", DEFAULT_LIMIT)
        if (offset < 0) throw BadRequestResponse("offset must be non-negative")
        if (limit !in 1..MAX_LIMIT) throw BadRequestResponse("limit must be between 1 and $MAX_LIMIT")
        return offset to limit
    }

    fun <T> page(items: List<T>, offset: Int, limit: Int): Page<T> {
        val from = offset.coerceAtMost(items.size)
        val to = (from + limit).coerceAtMost(items.size)
        val page = items.subList(from, to)
        return Page(page, offset, limit, page.size, items.size)
    }

    private fun parseInt(value: String?, name: String, default: Int): Int {
        if (value == null) return default
        return value.toIntOrNull() ?: throw BadRequestResponse("$name must be an integer")
    }
}
