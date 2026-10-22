package com.example.rotasmaisentregas

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
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
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.maps.plugin.viewport.viewport
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val listaParadas = mutableListOf<Parada>()
    private lateinit var paradaAdapter: ParadaAdapter
    private var jaPediuAcessibilidadeNestaSessao = false

    private var pointAnnotationManager: PointAnnotationManager? = null
    private val cacheGeocoding = ConcurrentHashMap<String, Point>()
    private var versaoAtualizacaoMapa = 0L
    private var ultimosPontosCamera: List<Point>? = null
    private var modoSeguirAtivo = false

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

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            ativarLocalizacaoSePermitido()
        } else {
            Toast.makeText(this, "Localização desativada. O mapa exibirá apenas a rota.", Toast.LENGTH_SHORT).show()
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

        configurarMapa()
        configurarSpinner()
        configurarRecyclerView()
        carregarUltimosEnderecos()

        binding.btnAtivarAcessibilidade.setOnClickListener {
            abrirConfiguracoesAcessibilidade()
        }

        binding.btnAdicionarParada.setOnClickListener {
            adicionarParadaManualmente()
        }

        binding.btnLimparOrigem.setOnClickListener {
            binding.txtOrigem.setText("")
            salvarUltimosEnderecos()
        }

        binding.btnRefresh.setOnClickListener {
            executarRefreshCaptura()
        }

        binding.btnCarregarPrint.setOnClickListener {
            selecionarPrintLauncher.launch("image/*")
        }

        binding.btnCentralizarMim.setOnClickListener {
            centralizarEmMim()
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

    private fun configurarMapa() {
        binding.mapView.mapboxMap.loadStyle(Style.MAPBOX_STREETS) {
            val initialCamera = CameraOptions.Builder()
                .center(Point.fromLngLat(-47.332, -22.738))
                .zoom(13.5)
                .build()
            binding.mapView.mapboxMap.setCamera(initialCamera)
            pointAnnotationManager = binding.mapView.annotations.createPointAnnotationManager()

            // Desliga o modo seguir se o usuário arrastar o mapa manualmente
            binding.mapView.gestures.addOnMoveListener(object : OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    modoSeguirAtivo = false
                }
                override fun onMove(detector: MoveGestureDetector): Boolean = false
                override fun onMoveEnd(detector: MoveGestureDetector) {}
            })

            // Ajusta margem inferior do logo e atribuição do Mapbox para não ficarem sob os botões flutuantes
            binding.mapView.attribution.updateSettings {
                marginBottom = 180f
            }
            binding.mapView.logo.updateSettings {
                marginBottom = 180f
            }

            // Solicita permissões de localização em tempo de execução ao abrir o mapa
            verificarESolicitarPermissoesLocalizacao()

            // Atualiza os marcadores caso a geocodificação tenha terminado antes do estilo carregar
            atualizarMapaComParadas()
        }
    }

    private fun verificarESolicitarPermissoesLocalizacao() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            ativarLocalizacaoSePermitido()
        } else {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun ativarLocalizacaoSePermitido() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            binding.mapView.location.updateSettings {
                enabled = true
                locationPuck = createDefault2DPuck(withBearing = true)
            }
        }
    }

    private fun centralizarEmMim() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            verificarESolicitarPermissoesLocalizacao()
            return
        }

        ativarLocalizacaoSePermitido()
        modoSeguirAtivo = true

        val followPuckState = binding.mapView.viewport.makeFollowPuckViewportState(
            FollowPuckViewportStateOptions.Builder()
                .zoom(16.0)
                .build()
        )

        binding.mapView.viewport.transitionTo(followPuckState)
    }

    private fun criarBitmapBolinha(concluida: Boolean): Bitmap {
        val size = 56
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (concluida) Color.GRAY else Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paintCircle)

        val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paintStroke)

        return bitmap
    }

    private fun calcularPapelParada(indexValido: Int, totalValidas: Int): String {
        if (totalValidas <= 1) return "Parada"
        return when (indexValido) {
            0 -> "Coleta"
            totalValidas - 1 -> "Destino"
            else -> "Parada ${indexValido + 1}"
        }
    }

    private fun atualizarMapaComParadas() {
        val versaoAtual = ++versaoAtualizacaoMapa
        val snapshot = listaParadas.toList()

        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val paradasValidas = snapshot.filter { it.endereco.isNotBlank() }
                val totalValidas = paradasValidas.size

                val pontosComInfo = mutableListOf<Triple<Point, Parada, String>>()

                for ((idx, parada) in paradasValidas.withIndex()) {
                    val enderecoLimpo = limparParaGoogleMaps(parada.endereco)
                    val papel = calcularPapelParada(idx, totalValidas)

                    val pontoCached = cacheGeocoding[enderecoLimpo]
                    if (pontoCached != null) {
                        pontosComInfo.add(Triple(pontoCached, parada, papel))
                    } else {
                        @Suppress("DEPRECATION")
                        val enderecos = geocoder.getFromLocationName(enderecoLimpo, 1)
                        if (!enderecos.isNullOrEmpty()) {
                            val loc = enderecos[0]
                            val ponto = Point.fromLngLat(loc.longitude, loc.latitude)
                            cacheGeocoding[enderecoLimpo] = ponto
                            pontosComInfo.add(Triple(ponto, parada, papel))
                        }
                    }
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (versaoAtual != versaoAtualizacaoMapa) return@runOnUiThread

                    val manager = pointAnnotationManager ?: return@runOnUiThread
                    manager.deleteAll()

                    val bitmapNormal = criarBitmapBolinha(false)
                    val bitmapConcluida = criarBitmapBolinha(true)

                    val pontosLocais = mutableListOf<Point>()

                    for (item in pontosComInfo) {
                        val ponto = item.first
                        val parada = item.second
                        val papel = item.third
                        pontosLocais.add(ponto)

                        val options = PointAnnotationOptions()
                            .withPoint(ponto)
                            .withIconImage(if (parada.concluida) bitmapConcluida else bitmapNormal)
                            .withIconOpacity(if (parada.concluida) 0.4 else 1.0)
                            .withTextField(papel)
                            .withTextAnchor(TextAnchor.BOTTOM)
                            .withTextOffset(listOf(0.0, -1.5))
                            .withTextSize(12.0)
                            .withTextColor("#000000")
                            .withTextHaloColor("#FFFFFF")
                            .withTextHaloWidth(2.0)
                            .withTextOpacity(if (parada.concluida) 0.4 else 1.0)

                        manager.create(options)
                    }

                    // Câmera inteligente (só move se modoSeguirAtivo for FALSE e a lista de pontos mudar)
                    if (!modoSeguirAtivo && ultimosPontosCamera != pontosLocais) {
                        ultimosPontosCamera = pontosLocais
                        if (pontosLocais.isNotEmpty()) {
                            if (pontosLocais.size == 1) {
                                val cameraOptions = CameraOptions.Builder()
                                    .center(pontosLocais[0])
                                    .zoom(15.0)
                                    .build()
                                binding.mapView.mapboxMap.setCamera(cameraOptions)
                            } else {
                                @Suppress("DEPRECATION")
                                val cameraOptions = binding.mapView.mapboxMap.cameraForCoordinates(
                                    pontosLocais,
                                    EdgeInsets(80.0, 80.0, 80.0, 80.0),
                                    null,
                                    null
                                )
                                binding.mapView.mapboxMap.setCamera(cameraOptions)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MAPA", "Erro ao atualizar marcadores no Mapbox", e)
            }
        }.start()
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
        binding.txtOrigem.setText("")
        listaParadas.clear()
        paradaAdapter.notifyDataSetChanged()

        val prefs = getSharedPreferences("enderecos_cache", MODE_PRIVATE)
        prefs.edit().clear().apply()

        atualizarBotaoRota()
        atualizarMapaComParadas()
        Toast.makeText(this, "Todas as rotas foram resetadas!", Toast.LENGTH_SHORT).show()
    }

    private fun executarRefreshCaptura() {
        if (!CapturaTelaService.estaAtivo()) {
            Toast.makeText(this, "Ative a Acessibilidade para usar o Refresh", Toast.LENGTH_LONG).show()
            abrirConfiguracoesAcessibilidade()
            return
        }

        Toast.makeText(this, "Abra o app de entrega na tela da corrida... Capturando em 1s!", Toast.LENGTH_SHORT).show()
        CapturaTelaService.forcarCaptura(1000L)
    }

    private fun salvarUltimosEnderecos() {
        val prefs = getSharedPreferences("enderecos_cache", MODE_PRIVATE)
        prefs.edit().apply {
            putString("origem", binding.txtOrigem.text.toString())
            putString("paradas_json", ParadaStorage.toJson(listaParadas))
            apply()
        }
        atualizarMapaComParadas()
    }

    private fun carregarUltimosEnderecos() {
        val prefs = getSharedPreferences("enderecos_cache", MODE_PRIVATE)
        var origemSalva = prefs.getString("origem", "") ?: ""
        if (origemSalva.equals("Minha localização atual", ignoreCase = true)) {
            origemSalva = ""
        }
        binding.txtOrigem.setText(origemSalva)

        val paradasJson = prefs.getString("paradas_json", "") ?: ""
        val paradasSalvas = ParadaStorage.fromJson(paradasJson)

        listaParadas.clear()
        listaParadas.addAll(paradasSalvas)
        paradaAdapter.notifyDataSetChanged()
        atualizarBotaoRota()
        atualizarMapaComParadas()
    }

    private fun atualizarBotaoRota() {
        val pendentes = listaParadas.count { !it.concluida && it.endereco.isNotBlank() }
        binding.btnRota.text = if (pendentes > 0) "GERAR ROTA ($pendentes)" else "GERAR ROTA"
    }
}