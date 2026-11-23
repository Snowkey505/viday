package com.vidayapi.model

data class PageRequest(
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    fun normalized(maxSize: Int = MAX_SIZE): PageRequest {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, maxSize)
        return copy(page = safePage, size = safeSize)
    }

    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    val totalPages: Int
        get() = if (size == 0 || totalElements == 0L) {
            0
        } else {
            ((totalElements + size - 1) / size).toInt()
        }

    val hasNext: Boolean get() = page < totalPages - 1
    val hasPrevious: Boolean get() = page > 0

    fun <R> map(transform: (T) -> R): Page<R> =
        Page(
            items = items.map(transform),
            page = page,
            size = size,
            totalElements = totalElements,
        )
}
