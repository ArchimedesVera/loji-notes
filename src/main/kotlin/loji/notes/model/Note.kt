package loji.notes.model

/**
 * 一张注释卡。
 *
 * [line] 是 0 基行号；[fingerprint] 是锚点行的规范化指纹，用于代码漂移后重新定位。
 * [dx]/[dy] 是卡片左上角相对锚点（行尾）的像素偏移，拖动卡片时更新。
 */
data class Note(
    val id: String,
    val line: Int,
    val fingerprint: String,
    val ctxBefore: List<String> = emptyList(),
    val ctxAfter: List<String> = emptyList(),
    val text: String = "",
    val color: String = Palette.DEFAULT_ID,
    val dx: Int = DEFAULT_DX,
    val dy: Int = DEFAULT_DY,
    val collapsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val author: String = "",
    /** 运行期标记：上下文对不上、重定位失败时为 true，不落盘。 */
    val detached: Boolean = false,
) {
    companion object {
        const val DEFAULT_DX = 64
        const val DEFAULT_DY = -18
    }

    fun movedTo(x: Int, y: Int): Note = copy(dx = x, dy = y, updatedAt = System.currentTimeMillis())
}
