package com.example.rotasmaisentregas

data class ResultadoEndereco(
    val coleta: String,
    val destinos: List<String>,
    val appIdentificado: String
)

object EnderecoExtractor {

    // ---------------------------------------------------------------
    // REGEX (sempre casando a LINHA INTEIRA, nunca "contém")
    // ---------------------------------------------------------------

    // "OS 779609877", "0S 779609877", "OS#779609877", "OS779609877"
    private val REGEX_OS = Regex("""^[O0]S\s*[#:]?\s*\d{5,}""", RegexOption.IGNORE_CASE)

    // "1º parada", "2° parada", "1ª parada", "1o parada", "Parada 1"
    // NÃO casa com "R$ 6,60 (1,70 km - 1 parada)" nem com "parada)"
    private val REGEX_MARCA_PARADA =
        Regex("""^(\d+\s*[ºo°ª]?\s*parada|parada\s*\d+)\s*:?$""", RegexOption.IGNORE_CASE)

    // "Cacau Show - (1,10 km)"  -> loja + distância (NÃO é endereço)
    private val REGEX_LOJA_DISTANCIA =
        Regex("""^.+\s*-\s*\(\s*\d+[.,]\d+\s*km\s*\)$""", RegexOption.IGNORE_CASE)

    private val REGEX_SO_KM = Regex("""^\(?\s*\d+[.,]?\d*\s*(km|m|min)\s*\)?$""", RegexOption.IGNORE_CASE)
    private val REGEX_HORA = Regex("""^\d{1,2}:\d{2}$""")
    private val REGEX_SO_NUMERO = Regex("""^[\d\s%.,)(\-]+$""")           // "94", "94%", "9ll 94)"
    private val REGEX_BOTAO = Regex("""^(aceitar|recusar)\b""", RegexOption.IGNORE_CASE)
    private val REGEX_RETORNO = Regex("""\b(retorno|retornar|voltar)\b""", RegexOption.IGNORE_CASE)
    private val REGEX_LADO_RUA = Regex("""\(\s*lado\s+(ímpar|impar|par)\s*\)""", RegexOption.IGNORE_CASE)

    // Frases longas: seguro usar "contém"
    private val RUIDO_CONTEM = listOf(
        "aplicativos ativos", "fechar tudo", "desconectado", "ficar livre",
        "seus horários", "ver horários", "escolher horários", "tela inicial",
        "carregar print", "gerar rota", "nova rota", "paradas da rota",
        "filtro para print", "taxa de aceitação", "detalhes da rota",
        "entrega pendente", "entrega já aceita", "ver no mapa",
        "carteira de créditos", "nova delivery", "yes delivery", "minha localização"
    )

    // Palavras curtas: só descarta se a linha for EXATAMENTE isso
    // (assim "Rua 99", "Av. do Perfil" ou "Rua Suporte" não são descartadas)
    private val RUIDO_EXATO = setOf(
        "entrega", "nuvem", "coleta", "menu", "ajuda", "início", "avisos",
        "configurações", "suporte", "ganhos", "histórico", "perfil", "finanças",
        "financeiro", "saldo", "offline", "ficar online", "cancelar", "conectar",
        "aceitar", "recusar", "99", "99pop", "99motorista", "ifood", "whatsapp",
        "tiktok", "sos", "modal", "opções", "página inicial"
    )

    private fun ehLinhaOS(linha: String) = REGEX_OS.containsMatchIn(linha.trim())

    private fun ehMarcaParada(linha: String) = REGEX_MARCA_PARADA.matches(linha.trim())

    private fun ehRuido(linha: String): Boolean {
        val t = linha.trim()
        val min = t.lowercase()
        if (min.length < 3) return true
        if (REGEX_HORA.matches(t) || REGEX_SO_NUMERO.matches(t) || REGEX_SO_KM.matches(t)) return true
        if (REGEX_LOJA_DISTANCIA.matches(t)) return true
        if (ehLinhaOS(t) || ehMarcaParada(t)) return true
        if (min.startsWith("r$") || min.contains("r$ ") || min.contains("/km")) return true
        if (min in RUIDO_EXATO) return true
        if (RUIDO_CONTEM.any { min.contains(it) }) return true
        if (REGEX_BOTAO.containsMatchIn(t)) return true
        return false
    }

    fun ehEnderecoValido(linha: String): Boolean = !ehRuido(linha)

    // ---------------------------------------------------------------
    // ENTRADA PRINCIPAL
    // ---------------------------------------------------------------

    fun extrairEnderecos(linhas: List<String>, appSelecionado: String = "AUTO"): ResultadoEndereco {
        val brutas = linhas.map { it.trim() }.filter { it.isNotBlank() }

        val app = if (appSelecionado == "AUTO") detectarApp(brutas) else appSelecionado

        val resultadoBruto = when (app.uppercase()) {
            "IFOOD" -> extrairIfood(mesclarLinhasQuebradas(brutas))
            "99" -> extrair99(mesclarLinhasQuebradas(brutas))
            // Taximachine NÃO usa mesclarLinhasQuebradas: a segmentação por âncoras já une os fragmentos
            else -> extrairTaximachine(brutas, app)
        }

        val coleta = limparEndereco(resultadoBruto.coleta)
        val destinos = resultadoBruto.destinos
            .map { limparEndereco(it) }
            .filter { it.isNotBlank() && ehEnderecoValido(it) }

        return ResultadoEndereco(
            coleta = if (ehEnderecoValido(coleta)) completarCidade(coleta, destinos) else "",
            destinos = destinos,
            appIdentificado = resultadoBruto.appIdentificado
        )
    }

    // ---------------------------------------------------------------
    // NOVA DELIVERY / YES DELIVERY (Taximachine)
    //
    // Layout (cima -> baixo):
    //   cabeçalho (preço, "1 parada)", badges)  -> IGNORADO
    //   OS 779609877                            -> âncora 1
    //   Cacau Show - (1,10 km)                  -> âncora 2 (loja, descartada)
    //   <fragmentos do endereço de COLETA>
    //   1º parada                               -> marcador
    //   <fragmentos do endereço da ENTREGA 1>
    //   2º parada ...                           -> marcador
    //   Aceitar / Recusar                       -> fim
    //
    // Ideia: segmentar por âncoras e juntar TODOS os fragmentos de cada
    // segmento com espaço. Não depende de adivinhar onde a rua quebrou.
    // ---------------------------------------------------------------
    private fun extrairTaximachine(linhas: List<String>, nomeApp: String): ResultadoEndereco {
        // 1) Corta tudo a partir dos botões Aceitar/Recusar
        val idxBotao = linhas.indexOfFirst { REGEX_BOTAO.containsMatchIn(it) }
        val corpo = if (idxBotao == -1) linhas else linhas.subList(0, idxBotao)

        // 2) Começa depois da última âncora (OS ou loja+distância). Sem âncora: começa do 0.
        val idxOS = corpo.indexOfFirst { ehLinhaOS(it) }
        val idxLoja = corpo.indexOfFirst { REGEX_LOJA_DISTANCIA.matches(it.trim()) }
        val inicio = maxOf(idxOS, idxLoja) + 1

        // 3) Percorre acumulando fragmentos no segmento atual
        val partesColeta = mutableListOf<String>()
        val entregas = mutableListOf<MutableList<String>>()
        var atual: MutableList<String> = partesColeta
        var temRetorno = false

        for (linha in corpo.subList(inicio, corpo.size)) {
            when {
                ehMarcaParada(linha) -> {
                    atual = mutableListOf()
                    entregas.add(atual)
                }
                REGEX_RETORNO.containsMatchIn(linha) -> temRetorno = true
                ehRuido(linha) -> Unit
                else -> atual.add(linha)
            }
        }

        val coleta = juntar(partesColeta)
        val destinos = entregas.map { juntar(it) }.filter { it.isNotBlank() }.toMutableList()

        // Obs.: o MainActivity/Service deduplica por texto, então este retorno
        // só entra na lista se a dedupe permitir endereço repetido.
        if (temRetorno && coleta.isNotBlank()) destinos.add(coleta)

        return ResultadoEndereco(coleta, destinos, nomeApp)
    }

    private fun juntar(partes: List<String>): String =
        partes.joinToString(" ").replace(Regex("""\s+"""), " ").trim()

    // ---------------------------------------------------------------
    // iFood e 99 (lógica original, só trocando os helpers)
    // ---------------------------------------------------------------
    private fun extrairIfood(linhas: List<String>): ResultadoEndereco {
        var coleta = ""
        val destinos = mutableListOf<String>()

        val candidatas = linhas.filter { ehEnderecoValido(it) }

        val idxColeta = candidatas.indexOfFirst {
            val min = it.lowercase()
            min.contains("coleta") || min.contains("retirar") || min.contains("restaurante")
        }
        if (idxColeta != -1 && idxColeta + 1 < candidatas.size) {
            coleta = candidatas[idxColeta + 1]
        }

        val idxEntrega = candidatas.indexOfFirst {
            val min = it.lowercase()
            min.contains("entrega") || min.contains("entregar em") || min.contains("cliente")
        }
        if (idxEntrega != -1 && idxEntrega + 1 < candidatas.size) {
            destinos.add(candidatas[idxEntrega + 1])
        }

        if (coleta.isEmpty()) coleta = candidatas.getOrNull(0) ?: ""
        if (destinos.isEmpty() && candidatas.size > 1) destinos.add(candidatas[1])

        return ResultadoEndereco(coleta, destinos, "iFood")
    }

    private fun extrair99(linhas: List<String>): ResultadoEndereco {
        val idxKm = linhas.indexOfFirst { it.lowercase().contains("km") }

        val candidatas = linhas.filterIndexed { index, linha ->
            val ehDepoisDoKm = if (idxKm != -1) index > idxKm else true
            ehDepoisDoKm && ehEnderecoValido(linha)
        }

        val coleta = candidatas.getOrNull(0) ?: ""
        val destinos = if (candidatas.size > 1) listOf(candidatas[1]) else emptyList()

        return ResultadoEndereco(coleta, destinos, "99")
    }

    // Usado só por iFood/99
    private fun mesclarLinhasQuebradas(linhas: List<String>): List<String> {
        if (linhas.isEmpty()) return emptyList()
        val resultado = mutableListOf<String>()
        var acumulador = ""

        for (linha in linhas) {
            val min = linha.lowercase()
            if (ehLinhaOS(linha) || ehMarcaParada(linha) || min.contains("aceitar") || min.contains("recusar")) {
                if (acumulador.isNotBlank()) {
                    resultado.add(acumulador)
                    acumulador = ""
                }
                resultado.add(linha)
            } else if (acumulador.isNotBlank()) {
                resultado.add("$acumulador $linha")
                acumulador = ""
            } else if (linha.endsWith("-") || linha.endsWith(",") ||
                min.endsWith(" sao") || min.endsWith(" são") ||
                min.endsWith(" de") || min.endsWith(" da") || min.endsWith(" do")
            ) {
                acumulador = linha
            } else {
                resultado.add(linha)
            }
        }
        if (acumulador.isNotBlank()) resultado.add(acumulador)
        return resultado
    }

    // ---------------------------------------------------------------
    // Limpeza final do endereço (para o Google Maps entender melhor)
    // ---------------------------------------------------------------
    private val REGEX_PREFIXO = Regex(
        """^(retirada|coleta|origem|entrega|destino|entregar em)\s*[:\-]\s*""",
        RegexOption.IGNORE_CASE
    )

    private fun limparEndereco(texto: String): String {
        var t = texto.trim()
        t = REGEX_PREFIXO.replace(t, "")
        t = REGEX_LADO_RUA.replace(t, "")          // remove "(lado ímpar)" / "(lado par)"
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun detectarApp(linhas: List<String>): String {
        val texto = linhas.joinToString(" ").lowercase()
        return when {
            texto.contains("ifood") || texto.contains("coleta no restaurante") -> "IFOOD"
            texto.contains("yes delivery") || texto.contains("yesdelivery") -> "YES_DELIVERY"
            else -> "NOVA_DELIVERY"
        }
    }

    private fun completarCidade(coleta: String, destinos: List<String>): String {
        if (coleta.isBlank() || coleta.contains(",")) return coleta
        val cidade = destinos.firstOrNull()
            ?.takeIf { it.contains(",") }
            ?.substringAfterLast(",")?.trim()
            .orEmpty()
        return if (cidade.isNotBlank()) "$coleta, $cidade" else coleta
    }
}