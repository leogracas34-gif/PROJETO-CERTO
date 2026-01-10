package com.vltv.play

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import retrofit2.Response
import kotlin.coroutines.CoroutineContext
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SearchActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var etQuery: EditText
    private lateinit var btnDoSearch: ImageButton
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SearchResultAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    
    private var searchJob: Job? = null
    private val searchCache = mutableMapOf<String, List<SearchResultItem>>()
    private val supervisor = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + supervisor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        val windowInsetsController = WindowCompat.getInsetsController(window,
        window.decorView)
        windowInsetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        etQuery = findViewById(R.id.etQuery)
        btnDoSearch = findViewById(R.id.btnDoSearch)
        rvResults = findViewById(R.id.rvResults)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = SearchResultAdapter { item ->
            when (item.type) {
                "movie" -> {
                    val i = Intent(this, DetailsActivity::class.java)
                    i.putExtra("stream_id", item.id)
                    i.putExtra("name", item.title)
                    i.putExtra("icon", item.iconUrl ?: "")
                    i.putExtra("rating", item.extraInfo ?: "0.0")
                    startActivity(i)
                }
                "series" -> {
                    val i = Intent(this, SeriesDetailsActivity::class.java)
                    i.putExtra("series_id", item.id)
                    i.putExtra("name", item.title)
                    i.putExtra("icon", item.iconUrl ?: "")
                    i.putExtra("rating", item.extraInfo ?: "0.0")
                    startActivity(i)
                }
                "live" -> {
                    val i = Intent(this, PlayerActivity::class.java)
                    i.putExtra("stream_id", item.id)
                    i.putExtra("stream_type", "live")
                    i.putExtra("channel_name", item.title)
                    startActivity(i)
                }
            }
        }

        rvResults.layoutManager = GridLayoutManager(this, 5)
        rvResults.adapter = adapter
        rvResults.isFocusable = true
        rvResults.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        setupSearch()
        
        val initial = intent.getStringExtra("initial_query")
        if (!initial.isNullOrBlank()) {
            etQuery.setText(initial)
            debounceSearch(initial)
        }
    }

    private fun setupSearch() {
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.length > 2) {
                    debounceSearch(query)
                } else {
                    adapter.submitList(emptyList())
                    tvEmpty.visibility = View.GONE
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnDoSearch.setOnClickListener { 
            debounceSearch(etQuery.text.toString().trim()) 
        }

        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                debounceSearch(etQuery.text.toString().trim())
                true
            } else false
        }
    }

    private fun debounceSearch(query: String) {
        searchJob?.cancel()
        searchJob = launch {
            delay(300)
            executarBuscaOtimizada(query)
        }
    }

    private fun normalizar(text: String?): String = text?.trim()?.lowercase() ?: ""

    private suspend fun executarBuscaOtimizada(raw: String) {
        val qNorm = normalizar(raw)
        if (qNorm.isEmpty()) return

        searchCache[qNorm]?.let { cached ->
            withContext(Dispatchers.Main) {
                updateUI(cached)
            }
            return
        }

        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        adapter.submitList(emptyList())

        val prefs = getSharedPreferences("vltv_prefs", MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        try {
            val resultadosDeferred = listOf(
                async { buscarFilmes(username, password, qNorm) },
                async { buscarSeries(username, password, qNorm) },
                async { buscarCanais(username, password, qNorm) }
            )
            
            val todasResultados = resultadosDeferred.awaitAll().flatten()
            searchCache[qNorm] = todasResultados
            
            withContext(Dispatchers.Main) {
                updateUI(todasResultados)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                updateUI(emptyList())
            }
        } finally {
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun buscarFilmes(username: String, password: String, qNorm: String): List<SearchResultItem> {
        return withContext(Dispatchers.IO) {
            try {
                val response = XtreamApi.service.getAllVodStreams(username, password).execute()
                if (response.isSuccessful && response.body() != null) {
                    response.body()!!
                        .filter { normalizar(it.title ?: it.name).contains(qNorm) }
                        .map {
                            SearchResultItem(
                                id = it.id,
                                title = it.title ?: it.name,
                                type = "movie",
                                extraInfo = it.rating,
                                iconUrl = it.icon
                            )
                        }
                } else emptyList()
            } catch (e: Exception) { emptyList() }
        }
    }

    private suspend fun buscarSeries(username: String, password: String, qNorm: String): List<SearchResultItem> {
        return withContext(Dispatchers.IO) {
            try {
                val response = XtreamApi.service.getAllSeries(username, password).execute()
                if (response.isSuccessful && response.body() != null) {
                    response.body()!!
                        .filter { normalizar(it.name).contains(qNorm) }
                        .map {
                            SearchResultItem(
                                id = it.id,
                                title = it.name,
                                type = "series",
                                extraInfo = it.rating,
                                iconUrl = it.icon
                            )
                        }
                } else emptyList()
            } catch (e: Exception) { emptyList() }
        }
    }

    private suspend fun buscarCanais(username: String, password: String, qNorm: String): List<SearchResultItem> {
        return withContext(Dispatchers.IO) {
            try {
                val response = XtreamApi.service.getLiveStreams(username, password, categoryId = "0").execute()
                if (response.isSuccessful && response.body() != null) {
                    response.body()!!
                        .filter { normalizar(it.name).contains(qNorm) }
                        .map {
                            SearchResultItem(
                                id = it.id,
                                title = it.name,
                                type = "live",
                                extraInfo = null,
                                iconUrl = it.stream_icon
                            )
                        }
                } else emptyList()
            } catch (e: Exception) { emptyList() }
        }
    }

    private fun updateUI(resultados: List<SearchResultItem>) {
        adapter.submitList(resultados)
        tvEmpty.visibility = if (resultados.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        supervisor.cancel()
    }
}
