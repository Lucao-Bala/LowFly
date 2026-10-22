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

    private val REGEX_OS = Regex("""^[O0]S\s*[#:]?\s*\d{5,}""", RegexOption.IGNORE_CASE)

    private val REGEX_MARCA_PARADA =
        Regex("""^(\d+\s*[ºo°ª]?\s*parada|parada\s*\d+)\s*:?$""", RegexOption.IGNORE_CASE)

    private val REGEX_LOJA_DISTANCIA =
        Regex("""^.+\s*-\s*\(\s*\d+[.,]\d+\s*km\s*\)$""", RegexOption.IGNORE_CASE)

    private val REGEX_SO_KM = Regex("""^\(?\s*\d+[.,]?\d*\s*(km|m|min)\s*\)?$""", RegexOption.IGNORE_CASE)
    private val REGEX_HORA = Regex("""^\d{1,2}:\d{2}$""")
    private val REGEX_SO_NUMERO = Regex("""^[\d\s%.,)(\-]+$""")
    private val REGEX_BOTAO = Regex("""^(aceitar|recusar)\b""", RegexOption.IGNORE_CASE)
    private val REGEX_RETORNO = Regex("""\b(retorno|retornar|voltar)\b""", RegexOption.IGNORE_CASE)
    private val REGEX_LADO_RUA = Regex("""\(\s*lado\s+(ímpar|impar|par)\s*\)""", RegexOption.IGNORE_CASE)
    private val REGEX_DATA_AGENDADA = Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b""")

    // Frases longas de telas do sistema/menus que NUNCA são endereços
    private val RUIDO_CONTEM = listOf(
        "anteced.", "antecedencia", "antecedência", "agendada para", "programada",
        "aplicativos ativos", "fechar tudo", "desconectado", "ficar livre",
        "seus horários", "ver horários", "escolher horários", "tela inicial",
        "carregar print", "gerar rota", "nova rota", "paradas da rota",
        "filtro para print", "taxa de aceitação", "detalhes da rota",
        "entrega pendente", "entrega já aceita", "ver no mapa",
        "carteira de créditos", "nova delivery", "yes delivery", "minha localização",
        "mapa livre", "corridas desconectar", "nenhuma entrega", "agendada para",
        "desconectar", "disp.", "pend.", "livre", "entregas", "mapa", "desafios"
    )

    private val RUIDO_EXATO = setOf(
        "entrega", "nuvem", "coleta", "menu", "ajuda", "início", "avisos",
        "configurações", "suporte", "ganhos", "histórico", "perfil", "finanças",
        "financeiro", "saldo", "offline", "ficar online", "cancelar", "conectar",
        "aceitar", "recusar", "whatsapp", "tiktok", "sos", "modal", "opções",
        "página inicial", "mapa", "corridas"
    )

    private fun ehLinhaOS(linha: String) = REGEX_OS.containsMatchIn(linha.trim())

    private fun ehMarcaParada(linha: String) = REGEX_MARCA_PARADA.matches(linha.trim())

    private fun ehRuido(linha: String): Boolean {
        val t = linha.trim()
        val min = t.lowercase()
        if (min.length < 3) return true
        if (REGEX_HORA.matches(t) || REGEX_SO_NUMERO.matches(t) || REGEX_SO_KM.matches(t) || REGEX_DATA_AGENDADA.containsMatchIn(t)) return true
        if (REGEX_LOJA_DISTANCIA.matches(t)) return true
        if (ehLinhaOS(t) || ehMarcaParada(t)) return true
        if (min.startsWith("r$") || min.contains("r$ ") || min.contains("/km")) return true
        if (min in RUIDO_EXATO) return true
        if (RUIDO_CONTEM.any { min.contains(it) }) return true
        if (REGEX_BOTAO.containsMatchIn(t)) return true
        return false
    }

    fun ehEnderecoValido(linha: String): Boolean = !ehRuido(linha)

    // Valida se a tela realmente exibe o título/aba de uma oferta ativa do Nova ou Yes
    fun ehTelaDeOfertaAtiva(linhas: List<String>, app: String): Boolean {
        val texto = linhas.joinToString(" ").lowercase()

        return when (app.uppercase()) {
            "YES_DELIVERY" -> texto.contains("delivery yes") || texto.contains("yes delivery") || texto.contains("os ") || texto.contains("1º parada") || texto.contains("1a parada")
            else -> texto.contains("nova delivery") || texto.contains("novadelivery") || texto.contains("os ") || texto.contains("1º parada") || texto.contains("1a parada")
        }
    }

    // ---------------------------------------------------------------
    // ENTRADA PRINCIPAL (Foco Exclusivo em Nova e Yes Delivery)
    // ---------------------------------------------------------------

    fun extrairEnderecos(linhas: List<String>, appSelecionado: String = "AUTO"): ResultadoEndereco {
        val brutas = linhas.map { it.trim() }.filter { it.isNotBlank() }

        val app = if (appSelecionado == "AUTO") detectarApp(brutas) else appSelecionado

        // Se a tela NÃO contiver a indicação de oferta ativa do Nova/Yes, aborta a leitura
        if (!ehTelaDeOfertaAtiva(brutas, app)) {
            return ResultadoEndereco(coleta = "", destinos = emptyList(), appIdentificado = app)
        }

        // Foco exclusivo no motor de entregas do Nova e Yes Delivery (Taximachine)
        val resultadoBruto = extrairTaximachine(brutas, app)

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

    private fun extrairTaximachine(linhas: List<String>, nomeApp: String): ResultadoEndereco {
        val idxBotao = linhas.indexOfFirst { REGEX_BOTAO.containsMatchIn(it) }
        val corpo = if (idxBotao == -1) linhas else linhas.subList(0, idxBotao)

        val idxOS = corpo.indexOfFirst { ehLinhaOS(it) }
        val idxLoja = corpo.indexOfFirst { REGEX_LOJA_DISTANCIA.matches(it.trim()) }
        val inicio = maxOf(idxOS, idxLoja) + 1

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

        if (temRetorno && coleta.isNotBlank()) destinos.add(coleta)

        return ResultadoEndereco(coleta, destinos, nomeApp)
    }

    private fun juntar(partes: List<String>): String =
        partes.joinToString(" ").replace(Regex("""\s+"""), " ").trim()

    // Métodos stubs preservados temporariamente para 99 e iFood (sem uso ativo no momento)
    @Suppress("unused")
    private fun extrairIfood(linhas: List<String>): ResultadoEndereco {
        return ResultadoEndereco("", emptyList(), "iFood")
    }

    @Suppress("unused")
    private fun extrair99(linhas: List<String>): ResultadoEndereco {
        return ResultadoEndereco("", emptyList(), "99")
    }

    private val REGEX_PREFIXO = Regex(
        """^(retirada|coleta|origem|entrega|destino|entregar em)\s*[:\-]\s*""",
        RegexOption.IGNORE_CASE
    )

    private fun limparEndereco(texto: String): String {
        var t = texto.trim()
        t = REGEX_PREFIXO.replace(t, "")
        t = REGEX_LADO_RUA.replace(t, "")
        t = t.replace(" . ", ". ").replace(" .", ".")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun detectarApp(linhas: List<String>): String {
        val texto = linhas.joinToString(" ").lowercase()
        return when {
            texto.contains("yes delivery") || texto.contains("yesdelivery") || texto.contains("delivery yes") -> "YES_DELIVERY"
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