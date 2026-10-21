package com.example.rotasmaisentregas

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CapturaTelaService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private fun ehPacotePermitido(pkgName: String): Boolean {
        val min = pkgName.lowercase()
        val eh99 = min.contains("taxis99") || min.contains("didiglobal.driver") || min == "com.app99" || min == "com.app99.driver"
        val ehIfood = min.contains("ifood") && min.contains("driver")
        val ehNova = min.contains("novadelivery") || min.contains("taximachine")
        val ehYes = min.contains("yesdelivery")

        return eh99 || ehIfood || ehNova || ehYes
    }

    private val lerTela = Runnable {
        val raiz = rootInActiveWindow ?: return@Runnable

        val pkgJanela = raiz.packageName?.toString() ?: return@Runnable
        if (!ehPacotePermitido(pkgJanela)) {
            Log.d("CAPTURA_DELIVERY", "Janela ativa ($pkgJanela) não é de aplicativo de delivery permitido. Leitura cancelada.")
            return@Runnable
        }

        val textos = mutableListOf<String>()
        coletarTextos(raiz, textos)

        Log.d("CAPTURA_DELIVERY", "=== TEXTOS ENCONTRADOS NA TELA ($pkgJanela) ===")
        textos.forEach {
            Log.d("CAPTURA_DELIVERY", it)
        }

        val resultado = EnderecoExtractor.extrairEnderecos(textos)
        Log.d("CAPTURA_DELIVERY", "=== RESULTADO EXTRAÍDO (${resultado.appIdentificado}) ===")
        Log.d("CAPTURA_DELIVERY", "Coleta: ${resultado.coleta}")
        Log.d("CAPTURA_DELIVERY", "Destinos: ${resultado.destinos.joinToString(", ")}")

        if (resultado.coleta.isNotEmpty() || resultado.destinos.isNotEmpty()) {
            salvarEmCache(resultado)
        }
    }
    private var leituraAgendada = false
    private val disparoLeitura = Runnable {
        leituraAgendada = false
        lerTela.run()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkgName = event?.packageName?.toString() ?: return
        if (!ehPacotePermitido(pkgName) || leituraAgendada) return

        leituraAgendada = true
        handler.postDelayed(disparoLeitura, 1500)
    }

    private fun coletarTextos(
        node: AccessibilityNodeInfo,
        textos: MutableList<String>,
    ) {
        val texto = node.text?.toString()?.trim()

        if (!texto.isNullOrEmpty()) {
            textos.add(texto)
        }

        for (i in 0 until node.childCount) {
            val filho = node.getChild(i) ?: continue
            coletarTextos(filho, textos)
        }
    }

    private fun salvarEmCache(resultado: ResultadoEndereco) {
        val prefs = getSharedPreferences("enderecos_cache", MODE_PRIVATE)
        val paradasJson = prefs.getString("paradas_json", "") ?: ""
        val paradasAtuais = ParadaStorage.fromJson(paradasJson).toMutableList()

        var alterou = false
        if (resultado.coleta.isNotBlank() && paradasAtuais.none { it.endereco.equals(resultado.coleta, ignoreCase = true) }) {
            paradasAtuais.add(Parada(endereco = resultado.coleta, appOrigem = resultado.appIdentificado))
            alterou = true
        }

        for (dest in resultado.destinos) {
            if (dest.isNotBlank() && paradasAtuais.none { it.endereco.equals(dest, ignoreCase = true) }) {
                paradasAtuais.add(Parada(endereco = dest, appOrigem = resultado.appIdentificado))
                alterou = true
            }
        }

        if (alterou) {
            prefs.edit().putString("paradas_json", ParadaStorage.toJson(paradasAtuais)).apply()
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(disparoLeitura)
        leituraAgendada = false
    }
}