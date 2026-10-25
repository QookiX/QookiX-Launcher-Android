package com.zhayi.qookix.control

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.zhayi.qookix.R

/**
 * 游戏内抽屉菜单的一项：文字 + 前置图标。
 *
 * @param label   显示文案
 * @param iconRes 图标资源；传 0 表示该项不显示图标
 */
data class MenuEntry(val label: String, @DrawableRes val iconRes: Int)

/**
 * 游戏内菜单 / 控制编辑器菜单的列表适配器。
 *
 * 只负责把 [MenuEntry] 渲染成 `R.layout.item_qk_menu`（圆角涟漪 + 强调色图标），
 * 条目数量与顺序完全由调用方给出的数组决定 —— 点击回调仍按 position 分发，
 * 与 Pojav 的 `ArrayAdapter` + `setOnItemClickListener` 用法保持一致。
 */
class QookixMenuAdapter(
    context: Context,
    entries: List<MenuEntry>
) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var items: List<MenuEntry> = entries

    fun submit(list: List<MenuEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): MenuEntry = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_qk_menu, parent, false)
        val entry = items[position]

        val icon = view.findViewById<ImageView>(R.id.qk_menu_icon)
        if (entry.iconRes != 0) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(entry.iconRes)
        } else {
            icon.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.qk_menu_label).text = entry.label
        return view
    }

    companion object {
        /**
         * 把 Pojav 的字符串数组与图标数组一一配对。
         *
         * 文案仍取上游数组（`menu_ingame` / `menu_customcontrol` …，含 zh-rCN 翻译），
         * 这样点击回调的 position 语义与 Pojav 完全一致；图标只是额外叠上去的。
         *
         * @param icons 图标资源；不足的项按 0（不显示图标）处理
         */
        @JvmStatic
        fun entriesOf(context: Context, arrayRes: Int, icons: IntArray): List<MenuEntry> =
            context.resources.getStringArray(arrayRes).mapIndexed { index, label ->
                MenuEntry(label, if (index < icons.size) icons[index] else 0)
            }
    }
}
