package loji.notes.model

/** 六色预设。id 落盘，颜色值只影响渲染。 */
data class NoteColor(val id: String, val label: String, val rgb: Int)

object Palette {
    const val DEFAULT_ID = "amber"

    val colors: List<NoteColor> = listOf(
        NoteColor("amber", "琥珀", 0xF2_B7_33),
        NoteColor("green", "青苔", 0x4C_AF_7D),
        NoteColor("blue", "钴蓝", 0x4A_9E_FF),
        NoteColor("purple", "紫藤", 0xA5_85_F5),
        NoteColor("red", "珊瑚", 0xF2_6D_6D),
        NoteColor("gray", "石墨", 0x8A_8F_98),
    )

    fun byId(id: String): NoteColor = colors.firstOrNull { it.id == id } ?: colors.first()

    fun nextId(id: String): String {
        val i = colors.indexOfFirst { it.id == id }
        return colors[(if (i < 0) 0 else i + 1) % colors.size].id
    }
}
