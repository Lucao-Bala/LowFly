package com.example.rotasmaisentregas

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

        binding.btnAdicionarParada.setOnClickListener {
            adicionarParadaManualmente()
        }

        binding.btnCarregarPrint.setOnClickListener {
            selecionarPrintLauncher.launch("image/*")
        }

        binding.btnRota.setOnClickListener {
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
                    val todasLinhas = visionText.textBlocks.flatMap { block ->
                        block.lines.map { it.text.trim() }
                    }.filter { it.isNotBlank() }

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
                    Log.d("PRINT_OCR", "Destino: ${resultado.destino}")

                    var novasAdicionadas = 0

                    if (resultado.coleta.isNotBlank() && listaParadas.none { it.endereco.equals(resultado.coleta, ignoreCase = true) }) {
                        listaParadas.add(Parada(endereco = resultado.coleta, appOrigem = resultado.appIdentificado))
                        novasAdicionadas++
                    }

                    if (resultado.destino.isNotBlank() && listaParadas.none { it.endereco.equals(resultado.destino, ignoreCase = true) }) {
                        listaParadas.add(Parada(endereco = resultado.destino, appOrigem = resultado.appIdentificado))
                        novasAdicionadas++
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
                }
                .addOnFailureListener { e ->
                    Log.e("PRINT_OCR", "Erro no OCR", e)
                    Toast.makeText(this, "Erro ao ler print: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Log.e("PRINT_OCR", "Erro ao carregar imagem", e)
            Toast.makeText(this, "Erro ao carregar imagem.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirGoogleMaps() {
        val origemDigitada = binding.txtOrigem.text.toString().trim()

        // Filtra apenas as paradas PENDENTES (não concluídas/não marcadas) e com endereço preenchido
        val paradasPendentes = listaParadas.filter { !it.concluida && it.endereco.isNotBlank() }

        if (paradasPendentes.isEmpty()) {
            Toast.makeText(this, "Nenhuma parada pendente na rota!", Toast.LENGTH_SHORT).show()
            return
        }

        val usarLocalizacaoAtual = origemDigitada.isEmpty() ||
                origemDigitada.equals("Minha localização", ignoreCase = true) ||
                origemDigitada.equals("Minha localização atual", ignoreCase = true)

        val urlBuilder = StringBuilder("https://www.google.com/maps/dir/?api=1")

        if (!usarLocalizacaoAtual) {
            urlBuilder.append("&origin=${Uri.encode(origemDigitada)}")
        }

        val destinoFinal = paradasPendentes.last().endereco
        val waypoints = paradasPendentes.dropLast(1).map { it.endereco }

        urlBuilder.append("&destination=${Uri.encode(destinoFinal)}")

        if (waypoints.isNotEmpty()) {
            urlBuilder.append("&waypoints=${Uri.encode(waypoints.joinToString("|"))}")
        }

        val url = Uri.parse(urlBuilder.toString())
        val intent = Intent(Intent.ACTION_VIEW, url)
        startActivity(intent)
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