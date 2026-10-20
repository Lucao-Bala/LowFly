package com.example.rotasmaisentregas

import java.util.UUID

data class Parada(
    val id: String = UUID.randomUUID().toString(),
    var endereco: String,
    var concluida: Boolean = false,
    val appOrigem: String = ""
)