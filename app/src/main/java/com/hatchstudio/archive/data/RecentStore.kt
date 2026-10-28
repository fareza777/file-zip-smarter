package com.hatchstudio.archive.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecentArchive(
    val uri: String,
    val name: String,
    val format: String,
    val size: Long,
    val entryCount: Int,
    val openedAt: Long,
    val treeUri: String? = null,
)

class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("hatch_recents", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecentArchive>> = _items.asStateFlow()

    private fun load(): List<RecentArchive> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val uri = o.optString("uri")
                    if (uri.isNotBlank()) {
                        add(
                            RecentArchive(
                                uri = uri,
                                name = o.optString("name", "Archive"),
                                format = o.optString("format", "ZIP"),
                                size = o.optLong("size"),
                                entryCount = o.optInt("entryCount"),
                                openedAt = o.optLong("openedAt"),
                                treeUri = o.optString("treeUri").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(item: RecentArchive) {
        val updated = (listOf(item) + _items.value.filterNot { it.uri == item.uri }).take(6)
        _items.value = updated
        persist(updated)
    }

    @Synchronized
    fun remove(uri: String) {
        val updated = _items.value.filterNot { it.uri == uri }
        _items.value = updated
        persist(updated)
    }

    fun clear() {
        _items.value = emptyList()
        persist(emptyList())
    }

    private fun persist(items: List<RecentArchive>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject().apply {
                    put("uri", it.uri)
                    put("name", it.name)
                    put("format", it.format)
                    put("size", it.size)
                    put("entryCount", it.entryCount)
                    put("openedAt", it.openedAt)
                    put("treeUri", it.treeUri ?: JSONObject.NULL)
                },
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
