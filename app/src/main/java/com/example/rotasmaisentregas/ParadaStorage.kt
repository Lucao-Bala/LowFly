package com.example.rotasmaisentregas

import org.json.JSONArray
import org.json.JSONObject

object ParadaStorage {

    fun toJson(paradas: List<Parada>): String {
        val array = JSONArray()
        for (p in paradas) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("endereco", p.endereco)
            obj.put("concluida", p.concluida)
            obj.put("appOrigem", p.appOrigem)
            array.put(obj)
        }
        return array.toString()
    }

    fun fromJson(jsonStr: String): List<Parada> {
        if (jsonStr.isBlank()) return emptyList()
        val list = mutableListOf<Parada>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val parada = Parada(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    endereco = obj.optString("endereco", ""),
                    concluida = obj.optBoolean("concluida", false),
                    appOrigem = obj.optString("appOrigem", "")
                )
                if (parada.endereco.isNotBlank()) {
                    list.add(parada)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}