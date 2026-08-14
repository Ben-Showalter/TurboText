package com.turbotext.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object BroadcastListHelper {
    private const val PREFS = "message_pro_broadcast_lists"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAllLists(context: Context): List<BroadcastList> {
        val raw = prefs(context).getString("lists", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val contactsArray = obj.getJSONArray("contacts")
                val contacts = (0 until contactsArray.length()).map { j ->
                    val c = contactsArray.getJSONObject(j)
                    BroadcastContact(c.getString("name"), c.getString("number"))
                }
                BroadcastList(obj.getString("id"), obj.getString("name"), contacts)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getList(context: Context, id: String): BroadcastList? =
        getAllLists(context).find { it.id == id }

    fun saveList(context: Context, list: BroadcastList) {
        val lists = getAllLists(context).toMutableList()
        val index = lists.indexOfFirst { it.id == list.id }
        if (index >= 0) lists[index] = list else lists.add(list)
        persistAll(context, lists)
    }

    fun deleteList(context: Context, id: String) {
        persistAll(context, getAllLists(context).filter { it.id != id })
    }

    fun createList(context: Context, name: String): BroadcastList {
        val list = BroadcastList(UUID.randomUUID().toString(), name, emptyList())
        saveList(context, list)
        return list
    }

    private fun persistAll(context: Context, lists: List<BroadcastList>) {
        val array = JSONArray()
        for (list in lists) {
            val obj = JSONObject()
            obj.put("id", list.id)
            obj.put("name", list.name)
            val contactsArray = JSONArray()
            for (c in list.contacts) {
                val cObj = JSONObject()
                cObj.put("name", c.name)
                cObj.put("number", c.number)
                contactsArray.put(cObj)
            }
            obj.put("contacts", contactsArray)
            array.put(obj)
        }
        prefs(context).edit().putString("lists", array.toString()).apply()
    }
}
