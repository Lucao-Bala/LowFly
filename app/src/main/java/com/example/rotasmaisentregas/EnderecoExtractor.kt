package com.example.rotasmaisentregas

data class ResultadoEndereco(
    val coleta: String,
    val destino: String,
    val appIdentificado: String
)

object EnderecoExtractor {

    fun ehEnderecoValido(linha: String): Boolean {
        val min = linha.lowercase().trim()
        if (min.length < 4) return false

        val palavrasProibidas = listOf(
            "aplicativos ativos", "fechar tudo", "desconectado", "ficar livre",
            "seus horários", "ver horários", "escolher horários", "tela inicial",
            "finanças", "financeiro", "ajuda", "menu", "início", "avisos",
            "configurações", "suporte", "ganhos", "histórico", "perfil",
            "página inicial", "carregar print", "gerar rota", "nova rota",
            "minha localização", "paradas da rota", "filtro para print",
            "opcao", "opções", "resposta", "sos", "whatsapp", "tiktok", "mudar na tela",
            "ficar online", "offline", "saldo", "taxa de aceitação", "cancelar", "conectar",
            "nuvem 1", "nuvem 2", "nuvem 3", "nuvem", "modal", "detalhes da rota",
            "entrega pendente", "entrega já aceita", "ver no mapa", "recusar", "aceitar"
        )

        if (palavrasProibidas.any { min.contains(it) }) return false

        // Filtro para IDs de pedido e ordens de serviço (ex: "OS 12345", "OS#12", "Pedido #123")
        if (min.startsWith("os ") || min.startsWith("os#") || min.startsWith("os:") || min.startsWith("id:") || min.startsWith("pedido #")) {
            return false
        }

        // Descarta linhas de preço, distância isolada e tempo
        if (min.startsWith("r$") || min.contains("r $") || min.endsWith("km") || min.endsWith("min") || min.endsWith("%")) {
            return false
        }

        return true
    }

    fun extrairEnderecos(linhas: List<String>, appSelecionado: String = "AUTO"): ResultadoEndereco {
        val linhasLimpas = linhas.map { it.trim() }.filter { it.isNotBlank() }

        val app = if (appSelecionado == "AUTO") {
            detectarApp(linhasLimpas)
        } else {
            appSelecionado
        }

        val resultadoBruto = when (app.uppercase()) {
            "IFOOD" -> extrairIfood(linhasLimpas)
            "NOVA_DELIVERY" -> extrairNovaDelivery(linhasLimpas)
            "YES_DELIVERY" -> extrairYesDelivery(linhasLimpas)
            else -> extrair99(linhasLimpas)
        }

        val coletaValidada = if (ehEnderecoValido(resultadoBruto.coleta)) resultadoBruto.coleta else ""
        val destinoValidado = if (ehEnderecoValido(resultadoBruto.destino)) resultadoBruto.destino else ""

        return ResultadoEndereco(
            coleta = coletaValidada,
            destino = destinoValidado,
            appIdentificado = resultadoBruto.appIdentificado
        )
    }

    private fun detectarApp(linhas: List<String>): String {
        val textoCompleto = linhas.joinToString(" ").lowercase()
        return when {
            textoCompleto.contains("ifood") || textoCompleto.contains("coleta no restaurante") -> "IFOOD"
            textoCompleto.contains("nova delivery") || textoCompleto.contains("novadelivery") || textoCompleto.contains("taximachine") -> "NOVA_DELIVERY"
            textoCompleto.contains("yes delivery") || textoCompleto.contains("yesdelivery") -> "YES_DELIVERY"
            else -> "99"
        }
    }

    private fun extrair99(linhas: List<String>): ResultadoEndereco {
        val idxKm = linhas.indexOfFirst { it.lowercase().contains("km") }

        val candidatas = linhas.filterIndexed { index, linha ->
            val min = linha.lowercase()
            val ehDepoisDoKm = if (idxKm != -1) index > idxKm else true

            ehDepoisDoKm &&
                    linha.length > 3 &&
                    !min.startsWith("r$") &&
                    !min.contains("r $") &&
                    !min.contains("km") &&
                    !min.contains("min") &&
                    !min.contains("aceitar") &&
                    !min.contains("recusar") &&
                    !min.contains("deslocamento") &&
                    !min.contains("corrida") &&
                    !min.contains("%")
        }.filter { ehEnderecoValido(it) }

        return ResultadoEndereco(
            coleta = candidatas.getOrNull(0) ?: "",
            destino = candidatas.getOrNull(1) ?: "",
            appIdentificado = "99"
        )
    }

    private fun extrairIfood(linhas: List<String>): ResultadoEndereco {
        var coleta = ""
        var destino = ""

        val candidatas = linhas.filter { linha ->
            val min = linha.lowercase()
            linha.length > 3 &&
                    !min.startsWith("r$") &&
                    !min.contains("km") &&
                    !min.contains("ganho") &&
                    !min.contains("detalhes") &&
                    !min.contains("aceitar")
        }.filter { ehEnderecoValido(it) }

        val idxColeta = candidatas.indexOfFirst {
            val min = it.lowercase()
            min.contains("coleta") || min.contains("retirar") || min.contains("restaurante")
        }
        if (idxColeta != -1 && idxColeta + 1 < candidatas.size) {
            val cand = candidatas[idxColeta + 1]
            if (ehEnderecoValido(cand)) coleta = cand
        }

        val idxEntrega = candidatas.indexOfFirst {
            val min = it.lowercase()
            min.contains("entrega") || min.contains("entregar em") || min.contains("cliente")
        }
        if (idxEntrega != -1 && idxEntrega + 1 < candidatas.size) {
            val cand = candidatas[idxEntrega + 1]
            if (ehEnderecoValido(cand)) destino = cand
        }

        if (coleta.isEmpty()) coleta = candidatas.getOrNull(0) ?: ""
        if (destino.isEmpty()) destino = candidatas.getOrNull(1) ?: ""

        return ResultadoEndereco(coleta, destino, "iFood")
    }

    private fun extrairNovaDelivery(linhas: List<String>): ResultadoEndereco {
        return extrairTaximachineOuNova(linhas, "Nova Delivery")
    }

    private fun extrairYesDelivery(linhas: List<String>): ResultadoEndereco {
        return extrairTaximachineOuNova(linhas, "Yes Delivery")
    }

    private fun extrairTaximachineOuNova(linhas: List<String>, nomeApp: String): ResultadoEndereco {
        var coleta = ""
        var destino = ""

        val candidatas = linhas.filter { ehEnderecoValido(it) }

        val idxColeta = candidatas.indexOfFirst {
            val min = it.lowercase()
            min.contains("retirada") || min.contains("coleta") || min.contains("origem")
        }
        if (idxColeta != -1 && idxColeta + 1 < candidatas.size) {
            val cand = candidatas[idxColeta + 1]
            if (ehEnderecoValido(cand)) coleta = cand
        }

        val idxEntrega = candidatas.indexOfFirst {
            val min = it.lowercase()
            min.contains("entrega") || min.contains("destino") || min.contains("entregar em")
        }
        if (idxEntrega != -1 && idxEntrega + 1 < candidatas.size) {
            val cand = candidatas[idxEntrega + 1]
            if (ehEnderecoValido(cand)) destino = cand
        }

        if (coleta.isEmpty()) coleta = candidatas.getOrNull(0) ?: ""
        if (destino.isEmpty()) destino = candidatas.getOrNull(1) ?: ""

        return ResultadoEndereco(coleta, destino, nomeApp)
    }
}