package com.example.rotasmaisentregas

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CapturaTelaService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var leituraAgendada = false

    private val PACOTES = mapOf(
        "com.taxis99" to "99",
        "com.app99" to "99",
        "com.app99.driver" to "99",
        "com.didiglobal.driver" to "99",
        "com.didiglobal.driver.pt" to "99",
        "com.xiaojukeji.didi.brazil.driver" to "99",
        "br.com.brainys.ifood.driver" to "IFOOD",
        "br.com.ifood.driver" to "IFOOD",
        "br.com.ifood.driver.app" to "IFOOD",
        "com.ifood.driver" to "IFOOD",
        "br.com.novadelivery.taxi.taximachine" to "NOVA_DELIVERY",
        "br.com.novadelivery.driver" to "NOVA_DELIVERY",
        "com.novadelivery.driver" to "NOVA_DELIVERY",
        "br.com.deliveryes.taxi.taximachine" to "YES_DELIVERY",
        "br.com.yesdelivery.driver" to "YES_DELIVERY",
        "com.yesdelivery.driver" to "YES_DELIVERY"
    )

    private fun obterNomeApp(pkgName: String): String? {
        val min = pkgName.lowercase()
        return PACOTES[pkgName] ?: PACOTES.entries.firstOrNull { min.contains(it.key.lowercase()) }?.value
    }

    private val lerTela = Runnable {
        val raiz = rootInActiveWindow ?: return@Runnable

        val pkgJanela = raiz.packageName?.toString() ?: return@Runnable
        val appIdentificado = obterNomeApp(pkgJanela) ?: run {
            Log.d("CAPTURA_DELIVERY", "Janela ativa ($pkgJanela) não é de aplicativo de delivery permitido. Leitura cancelada.")
            return@Runnable
        }

        val textos = mutableListOf<String>()
        coletarTextos(raiz, textos)

        Log.d("CAPTURA_DELIVERY", "=== TEXTOS ENCONTRADOS NA TELA ($pkgJanela) ===")
        textos.forEach {
            Log.d("CAPTURA_DELIVERY", it)
        }

        val resultado = EnderecoExtractor.extrairEnderecos(textos, appIdentificado)
        Log.d("CAPTURA_DELIVERY", "=== RESULTADO EXTRAÍDO (${resultado.appIdentificado}) ===")
        Log.d("CAPTURA_DELIVERY", "Coleta: ${resultado.coleta}")
        Log.d("CAPTURA_DELIVERY", "Destinos: ${resultado.destinos.joinToString(", ")}")

        if (resultado.coleta.isNotEmpty() && resultado.destinos.isNotEmpty()) {
            salvarEmCache(resultado)
        }
    }

    private val disparoLeitura = Runnable {
        leituraAgendada = false
        lerTela.run()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkgName = event?.packageName?.toString() ?: return
        if (obterNomeApp(pkgName) == null || leituraAgendada) return

        Log.d("CAPTURA_DELIVERY", ">>> App de delivery ativo ($pkgName)! Agendando leitura da tela...")

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
            emitirVibracaoSucesso()
        }
    }

    private fun emitirVibracaoSucesso() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(disparoLeitura)
        leituraAgendada = false
    }
}