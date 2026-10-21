package com.example.rotasmaisentregas

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rotasmaisentregas.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val listaParadas = mutableListOf<Parada>()
    private lateinit var paradaAdapter: ParadaAdapter
    private var jaPediuAcessibilidadeNestaSessao = false

    private val appsDisponiveis = arrayOf(
        "Detectar Automático",
        "99",
        "iFood",
        "Nova Delivery",
        "Yes Delivery"
    )

    private val selecionarPrintLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            processarPrint(uri)
        } else {
            Toast.makeText(this, "Nenhum print selecionado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        configurarSpinner()
        configurarRecyclerView()
        carregarUltimosEnderecos()

        binding.btnAtivarAcessibilidade.setOnClickListener {
            abrirConfiguracoesAcessibilidade()
        }

        binding.btnAdicionarParada.setOnClickListener {
            adicionarParadaManualmente()
        }

        binding.btnCarregarPrint.setOnClickListener {
            selecionarPrintLauncher.launch("image/*")
        }

        binding.btnRota.setOnClickListener {
            currentFocus?.clearFocus()
            salvarUltimosEnderecos()
            abrirGoogleMaps()
        }

        binding.btnNovaRota.setOnClickListener {
            novaRota()
        }
    }

    override fun onResume() {
        super.onResume()
        carregarUltimosEnderecos()
        verificarEAlertarAcessibilidade()
    }

    private fun verificarEAlertarAcessibilidade() {
        val estaAtivo = isAccessibilityServiceEnabled(this, CapturaTelaService::class.java)

        if (estaAtivo) {
            binding.bannerAcessibilidade.visibility = View.GONE
        } else {
            binding.bannerAcessibilidade.visibility = View.VISIBLE

            if (!jaPediuAcessibilidadeNestaSessao) {
                jaPediuAcessibilidadeNestaSessao = true
                mostrarDialogAcessibilidade()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val stringColonSplitter = TextUtils.SimpleStringSplitter(':')
        stringColonSplitter.setString(enabledServicesSetting)

        while (stringColonSplitter.hasNext()) {
            val componentNameString = stringColonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun mostrarDialogAcessibilidade() {
        AlertDialog.Builder(this)
            .setTitle("Permissão de Acessibilidade Necessária")
            .setMessage("Para capturar automaticamente os endereços quando as entregas surgirem na tela dos apps parceiros (99, iFood, Nova, Yes), é necessário ativar o serviço de acessibilidade do aplicativo.\n\nDeseja abrir as configurações para ativar agora?")
            .setPositiveButton("ATIVAR AGORA") { _, _ ->
                abrirConfiguracoesAcessibilidade()
            }
            .setNegativeButton("AGORA NÃO", null)
            .show()
    }

    private fun abrirConfiguracoesAcessibilidade() {
        try {
            val showArgs = "${packageName}/${CapturaTelaService::class.java.canonicalName}"
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", showArgs)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", showArgs)
                })
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun configurarSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            appsDisponiveis
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerApp.adapter = adapter
    }

    private fun configurarRecyclerView() {
        paradaAdapter = ParadaAdapter(listaParadas) {
            salvarUltimosEnderecos()
            atualizarBotaoRota()
        }
        binding.recyclerParadas.layoutManager = LinearLayoutManager(this)
        binding.recyclerParadas.adapter = paradaAdapter
    }

    private fun adicionarParadaManualmente() {
        listaParadas.add(Parada(endereco = "", appOrigem = "MANUAL"))
        paradaAdapter.notifyItemInserted(listaParadas.size - 1)
        salvarUltimosEnderecos()
        atualizarBotaoRota()
    }

    private fun processarPrint(imageUri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, imageUri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    runOnUiThread {
                        try {
                            val todasLinhas = visionText.textBlocks
                                .flatMap { it.lines }
                                .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                                .map { it.text.trim() }
                                .filter { it.isNotBlank() }

                            Log.d("PRINT_OCR", "=== TODAS AS LINHAS LIDAS DA IMAGEM ===")
                            todasLinhas.forEachIndexed { i, l -> Log.d("PRINT_OCR", "[$i] $l") }

                            val appSelecionado = when (binding.spinnerApp.selectedItemPosition) {
                                1 -> "99"
                                2 -> "IFOOD"
                                3 -> "NOVA_DELIVERY"
                                4 -> "YES_DELIVERY"
                                else -> "AUTO"
                            }

                            val resultado = EnderecoExtractor.extrairEnderecos(todasLinhas, appSelecionado)

                            Log.d("PRINT_OCR", "=== RESULTADO EXTRAÍDO (${resultado.appIdentificado}) ===")
                            Log.d("PRINT_OCR", "Coleta: ${resultado.coleta}")
                            Log.d("PRINT_OCR", "Destinos: ${resultado.destinos.joinToString(", ")}")

                            var novasAdicionadas = 0

                            if (resultado.coleta.isNotBlank() && listaParadas.none { it.endereco.equals(resultado.coleta, ignoreCase = true) }) {
                                listaParadas.add(Parada(endereco = resultado.coleta, appOrigem = resultado.appIdentificado))
                                novasAdicionadas++
                            }

                            for (dest in resultado.destinos) {
                                if (dest.isNotBlank() && listaParadas.none { it.endereco.equals(dest, ignoreCase = true) }) {
                                    listaParadas.add(Parada(endereco = dest, appOrigem = resultado.appIdentificado))
                                    novasAdicionadas++
                                }
                            }

                            if (novasAdicionadas > 0) {
                                paradaAdapter.notifyDataSetChanged()
                                salvarUltimosEnderecos()
                                atualizarBotaoRota()

                                Toast.makeText(
                                    this,
                                    "$novasAdicionadas nova(s) parada(s) do ${resultado.appIdentificado} adicionada(s)!",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(this, "Nenhuma nova parada encontrada no print.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Throwable) {
                            Log.e("PRINT_OCR", "Erro ao processar resultado do OCR", e)
                            Toast.makeText(this, "Erro ao processar imagem: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    runOnUiThread {
                        Log.e("PRINT_OCR", "Erro no OCR", e)
                        Toast.makeText(this, "Erro ao ler print: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        } catch (e: Exception) {
            Log.e("PRINT_OCR", "Erro ao carregar imagem", e)
            Toast.makeText(this, "Erro ao carregar imagem.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun limparParaGoogleMaps(endereco: String): String {
        var e = endereco.trim()

        e = e.replace(Regex("""\(.*?\)"""), "").trim()

        val ruidos = listOf("problema", "lado ímpar", "lado impar", "lado par")
        for (r in ruidos) {
            e = e.replace(Regex("(?i)\\b$r\\b"), "").trim()
        }

        if (e.startsWith("R. ", ignoreCase = true)) e = "Rua " + e.substring(3)
        if (e.startsWith("Av. ", ignoreCase = true)) e = "Avenida " + e.substring(4)
        if (e.startsWith("Al. ", ignoreCase = true)) e = "Alameda " + e.substring(4)
        if (e.startsWith("Pr. ", ignoreCase = true)) e = "Praça " + e.substring(4)

        if (e.contains("Santa Bárbara", ignoreCase = true) || e.endsWith("d'", ignoreCase = true)) {
            e = e.replace(Regex("(?i),?\\s*Santa Bárbara.*$"), "")
            e = "$e, Santa Bárbara d'Oeste - SP"
        } else if (e.endsWith("Ameri", ignoreCase = true) || e.endsWith("Americ", ignoreCase = true) || e.endsWith("Americana", ignoreCase = true)) {
            e = e.replace(Regex("(?i),?\\s*Ameri.*$"), "")
            e = "$e, Americana - SP"
        } else if (!e.contains("Americana", ignoreCase = true) && !e.contains("Santa Bárbara", ignoreCase = true)) {
            e = "$e, Americana - SP"
        }

        return e.replace(Regex("""\s+-\s+"""), ", ")
            .replace(Regex("""\s*,\s*,\s*"""), ", ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .removeSuffix(",")
            .removeSuffix("-")
            .trim()
    }

    private fun abrirGoogleMaps() {
        val origemDigitada = binding.txtOrigem.text.toString().trim()

        val paradasPendentes = listaParadas
            .filter { !it.concluida && it.endereco.isNotBlank() }
            .map { limparParaGoogleMaps(it.endereco) }
            .filter { it.isNotBlank() }

        if (paradasPendentes.isEmpty()) {
            Toast.makeText(this, "Nenhuma parada pendente na rota!", Toast.LENGTH_SHORT).show()
            return
        }

        val usarLocalizacaoAtual = origemDigitada.isEmpty() ||
                origemDigitada.equals("Minha localização", ignoreCase = true) ||
                origemDigitada.equals("Minha localização atual", ignoreCase = true)

        val urlBuilder = StringBuilder("https://www.google.com/maps/dir/?api=1")

        if (!usarLocalizacaoAtual) {
            val origemLimpa = limparParaGoogleMaps(origemDigitada)
            urlBuilder.append("&origin=${Uri.encode(origemLimpa)}")
        }

        val destinoFinal = paradasPendentes.last()
        val waypoints = paradasPendentes.dropLast(1)

        urlBuilder.append("&destination=${Uri.encode(destinoFinal)}")

        if (waypoints.isNotEmpty()) {
            val waypointsFormatados = waypoints.joinToString("|") { Uri.encode(it) }
            urlBuilder.append("&waypoints=$waypointsFormatados")
        }

        val url = Uri.parse(urlBuilder.toString())
        val intent = Intent(Intent.ACTION_VIEW, url)
        intent.setPackage("com.google.android.apps.maps")

        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, url)
            startActivity(fallbackIntent)
        }
    }

    private fun novaRota() {
        binding.txtOrigem.setText("Minha localização atual")
        listaParadas.clear()
        paradaAdapter.notifyDataSetChanged()

        val prefs = getSharedPreferences("enderecos_cache", MODE_PRIVATE)
        prefs.edit().clear().apply()

        atualizarBotaoRota()
        Toast.makeText(this, "Todas as rotas foram resetadas!", Toast.LENGTH_SHORT).show()
    }

    private fun salvarUltimosEnderecos() {
        val prefs = getSharedPreferences("enderecos_cache", MODE_PRIVATE)
        prefs.edit().apply {
            putString("origem", binding.txtOrigem.text.toString())
            putString("paradas_json", ParadaStorage.toJson(listaParadas))
            apply()
        }
    }

    private fun carregarUltimosEnderecos() {
        val prefs = getSharedPreferences("enderecos_cache", MODE_PRIVATE)
        val origemSalva = prefs.getString("origem", "Minha localização atual")
        binding.txtOrigem.setText(if (origemSalva.isNullOrBlank()) "Minha localização atual" else origemSalva)

        val paradasJson = prefs.getString("paradas_json", "") ?: ""
        val paradasSalvas = ParadaStorage.fromJson(paradasJson)

        listaParadas.clear()
        listaParadas.addAll(paradasSalvas)
        paradaAdapter.notifyDataSetChanged()
        atualizarBotaoRota()
    }

    private fun atualizarBotaoRota() {
        val pendentes = listaParadas.count { !it.concluida && it.endereco.isNotBlank() }
        binding.btnRota.text = if (pendentes > 0) "GERAR ROTA ($pendentes)" else "GERAR ROTA"
    }
}