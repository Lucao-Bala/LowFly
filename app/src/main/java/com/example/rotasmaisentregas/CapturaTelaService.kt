package com.example.rotasmaisentregas

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CapturaTelaService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private val lerTela = Runnable {
        val raiz = rootInActiveWindow ?: return@Runnable

        val textos = mutableListOf<String>()
        coletarTextos(raiz, textos)

        Log.d("CAPTURA_DELIVERY", "=== TEXTOS ENCONTRADOS NA TELA ===")
        textos.forEach {
            Log.d("CAPTURA_DELIVERY", it)
        }

        val resultado = EnderecoExtractor.extrairEnderecos(textos)
        Log.d("CAPTURA_DELIVERY", "=== RESULTADO EXTRAÍDO (${resultado.appIdentificado}) ===")
        Log.d("CAPTURA_DELIVERY", "Coleta: ${resultado.coleta}")
        Log.d("CAPTURA_DELIVERY", "Destino: ${resultado.destino}")

        if (resultado.coleta.isNotEmpty() || resultado.destino.isNotEmpty()) {
            salvarEmCache(resultado)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: return

        // Registra o pacote de QUALQUER aplicativo ativando a tela para você ver no Logcat
        Log.d("CAPTURA_DELIVERY", "Tela ativa detectada no pacote: $pkgName")

        val ehAppSuportado = pkgName.contains("99") ||
                pkgName.contains("didi", ignoreCase = true) ||
                pkgName.contains("driver", ignoreCase = true) ||
                pkgName.contains("ifood", ignoreCase = true) ||
                pkgName.contains("novadelivery", ignoreCase = true) ||
                pkgName.contains("yesdelivery", ignoreCase = true)

        if (!ehAppSuportado) return

        Log.d("CAPTURA_DELIVERY", ">>> App parceiro de delivery reconhecido ($pkgName)! Lendo tela...")

        handler.removeCallbacks(lerTela)
        handler.postDelayed(lerTela, 1500)
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
        if (resultado.destino.isNotBlank() && paradasAtuais.none { it.endereco.equals(resultado.destino, ignoreCase = true) }) {
            paradasAtuais.add(Parada(endereco = resultado.destino, appOrigem = resultado.appIdentificado))
            alterou = true
        }

        if (alterou) {
            prefs.edit().putString("paradas_json", ParadaStorage.toJson(paradasAtuais)).apply()
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(lerTela)
    }
}