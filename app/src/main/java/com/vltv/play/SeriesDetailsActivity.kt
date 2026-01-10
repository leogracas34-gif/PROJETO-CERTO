package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.ArrayList

class SeriesDetailsActivity : AppCompatActivity() {

    private var seriesId: Int = 0
    private var seriesName: String = ""
    private var seriesIcon: String? = null
    private var seriesRating: String = "0.0"

    // Views
    private lateinit var imgPoster: ImageView
    private lateinit var imgBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView
    private lateinit var tvPlot: TextView
    private lateinit var btnSeasonSelector: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var btnFavoriteSeries: ImageButton

    private lateinit var btnPlaySeries: Button
    private lateinit var btnDownloadEpisodeArea: LinearLayout
    private lateinit var imgDownloadEpisodeState: ImageView
    private lateinit var tvDownloadEpisodeState: TextView

    private lateinit var btnDownloadSeason: Button

    private var episodesBySeason: Map<String, List<EpisodeStream>> = emptyMap()
    private var sortedSeasons: List<String> = emptyList()
    private var currentSeason: String = ""
    private var currentEpisode: EpisodeStream? = null

    private enum class DownloadState { BAIXAR, BAIXANDO, BAIXADO }
    private var downloadState: DownloadState = DownloadState.BAIXAR

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series_details)

        seriesId = intent.getIntExtra("series_id", 0)
        seriesName = intent.getStringExtra("name") ?: ""
        seriesIcon = intent.getStringExtra("icon")
        seriesRating = intent.getStringExtra("rating") ?: "0.0"

        imgPoster = findViewById(R.id.imgPosterSeries)
        imgBackground = try { findViewById(R.id.imgBackgroundSeries) } catch (e: Exception) { imgPoster }
        tvCast = try { findViewById(R.id.tvSeriesCast) } catch (e: Exception) { findViewById(R.id.tvSeriesGenre) }

        tvTitle = findViewById(R.id.tvSeriesTitle)
        tvRating = findViewById(R.id.tvSeriesRating)
        tvGenre = findViewById(R.id.tvSeriesGenre)
        tvPlot = findViewById(R.id.tvSeriesPlot)
        btnSeasonSelector = findViewById(R.id.btnSeasonSelector)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        btnFavoriteSeries = findViewById(R.id.btnFavoriteSeries)

        btnPlaySeries = findViewById(R.id.btnPlaySeries)
        btnDownloadEpisodeArea = findViewById(R.id.btnDownloadSeriesArea)
        imgDownloadEpisodeState = findViewById(R.id.imgDownloadSeriesState)
        tvDownloadEpisodeState = findViewById(R.id.tvDownloadSeriesState)
        btnDownloadSeason = findViewById(R.id.btnDownloadSeason)

        if (isTelevisionDevice()) {
            btnDownloadEpisodeArea.visibility = View.GONE
            btnDownloadSeason.visibility = View.GONE
        }

        tvTitle.text = seriesName
        tvRating.text = "Nota: $seriesRating"
        tvGenre.text = "Gênero: Buscando..."
        tvCast.text = "Elenco: Buscando..."
        tvPlot.text = "Carregando sinopse..."

        btnSeasonSelector.setBackgroundColor(Color.parseColor("#333333"))

        Glide.with(this)
            .load(seriesIcon)
            .placeholder(R.mipmap.ic_launcher)
            .centerCrop()
            .into(imgPoster)

        rvEpisodes.isFocusable = true
        rvEpisodes.isFocusableInTouchMode = true
        rvEpisodes.setHasFixedSize(true)
        rvEpisodes.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4)
        
        // Listener de foco
        rvEpisodes.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val holder = rvEpisodes.findContainingViewHolder(view) as? EpisodeAdapter.VH
                holder?.let {
                    val position = holder.adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        currentEpisode = (rvEpisodes.adapter as? EpisodeAdapter)?.list?.getOrNull(position)
                        restaurarEstadoDownload()
                    }
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        val isFavInicial = getFavSeries(this).contains(seriesId)
        atualizarIconeFavoritoSerie(isFavInicial)

        btnFavoriteSeries.setOnClickListener {
            val favs = getFavSeries(this)
            val novoFav: Boolean
            if (favs.contains(seriesId)) {
                favs.remove(seriesId)
                novoFav = false
            } else {
                favs.add(seriesId)
                novoFav = true
            }
            saveFavSeries(this, favs)
            atualizarIconeFavoritoSerie(novoFav)
        }

        btnSeasonSelector.setOnClickListener { mostrarSeletorDeTemporada() }

        btnPlaySeries.setOnClickListener {
            val ep = currentEpisode
            if (ep == null) {
                Toast.makeText(this, "Selecione um episódio.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            abrirPlayer(ep, false)
        }

        restaurarEstadoDownload()

        btnDownloadEpisodeArea.setOnClickListener {
            val ep = currentEpisode ?: return@setOnClickListener
            when (downloadState) {
                DownloadState.BAIXAR -> {
                    val eid = ep.id.toIntOrNull() ?: 0
                    if (eid == 0) return@setOnClickListener
                    val url = montarUrlEpisodio(ep)
                    val safeTitle = seriesName.replace("[^a-zA-Z0-9 _.-]".toRegex(), "_").ifBlank { "serie" }
                    val fileName = "${safeTitle}_T${currentSeason}E${ep.episode_num}_${eid}.mp4"
                    DownloadHelper.enqueueDownload(this, url, fileName, logicalId = "series_$eid", type = "series")
                    Toast.makeText(this, "Baixando...", Toast.LENGTH_SHORT).show()
                    setDownloadState(DownloadState.BAIXANDO, ep)
                }
                DownloadState.BAIXANDO -> startActivity(Intent(this, DownloadsActivity::class.java))
                DownloadState.BAIXADO -> startActivity(Intent(this, DownloadsActivity::class.java))
            }
        }

        btnDownloadSeason.setOnClickListener {
            if (currentSeason.isBlank()) return@setOnClickListener
            val lista = episodesBySeason[currentSeason] ?: emptyList()
            if (lista.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Baixar temporada")
                .setMessage("Baixar todos os ${lista.size} episódios?")
                .setPositiveButton("Sim") { _, _ -> baixarTemporadaAtual(lista) }
                .setNegativeButton("Não", null)
                .show()
        }

        carregarSeriesInfo()
        sincronizarDadosTMDB()
    }

    private fun sincronizarDadosTMDB() {
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
        var cleanName = seriesName.replace(Regex("\\(\\d{4}\\)"), "")
        cleanName = cleanName.replace(Regex("[^A-Za-z0-9 ÁáÉéÍíÓóÚúÃãÕõÇç]"), "").trim()
        val encodedName = try { URLEncoder.encode(cleanName, "UTF-8") } catch(e:Exception) { cleanName }
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encodedName&language=pt-BR"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body()?.string()
                if (body != null) {
                    try {
                        val jsonObject = JSONObject(body)
                        val results = jsonObject.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val show = results.getJSONObject(0)
                            buscarDetalhesTMDB(show.getInt("id"), apiKey)
                            runOnUiThread {
                                val sinopse = show.optString("overview")
                                tvPlot.text = if (sinopse.isNotEmpty()) sinopse else "Sinopse indisponível."
                                val vote = show.optDouble("vote_average", 0.0)
                                if (vote > 0) tvRating.text = "Nota: ${String.format("%.1f", vote)}"
                                val backdropPath = show.optString("backdrop_path")
                                if (backdropPath.isNotEmpty() && imgBackground != imgPoster) {
                                    Glide.with(this@SeriesDetailsActivity)
                                        .load("https://image.tmdb.org/t/p/w1280$backdropPath")
                                        .centerCrop().into(imgBackground)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        })
    }

    private fun buscarDetalhesTMDB(id: Int, key: String) {
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=$key&append_to_response=credits&language=pt-BR"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body()?.string() ?: return
                try {
                    val d = JSONObject(body)
                    val gs = d.optJSONArray("genres")
                    val genresList = mutableListOf<String>()
                    if (gs != null) for (i in 0 until gs.length()) genresList.add(gs.getJSONObject(i).getString("name"))

                    val credits = d.optJSONObject("credits")
                    val castArray = credits?.optJSONArray("cast")
                    val castList = mutableListOf<String>()
                    if (castArray != null) {
                        val limit = if (castArray.length() > 5) 5 else castArray.length()
                        for (i in 0 until limit) castList.add(castArray.getJSONObject(i).getString("name"))
                    }

                    runOnUiThread {
                        tvGenre.text = "Gênero: ${if (genresList.isEmpty()) "Variados" else genresList.joinToString(", ")}"
                        tvCast.text = "Elenco: ${if (castList.isEmpty()) "Não informado" else castList.joinToString(", ")}"
                    }
                } catch(e: Exception) {}
            }
        })
    }

    override fun onResume() {
        super.onResume()
        restaurarEstadoDownload()
    }

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("fav_series", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun saveFavSeries(context: Context, ids: Set<Int>) {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("fav_series", ids.map { it.toString() }.toSet()).apply()
    }

    private fun atualizarIconeFavoritoSerie(isFav: Boolean) {
        if (isFav) {
            btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_on)
            btnFavoriteSeries.setColorFilter(Color.parseColor("#FFD700"))
        } else {
            btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_off)
            btnFavoriteSeries.clearColorFilter()
        }
    }

    private fun carregarSeriesInfo() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        XtreamApi.service.getSeriesInfoV2(username, password, seriesId = seriesId)
            .enqueue(object : Callback<SeriesInfoResponse> {
                override fun onResponse(call: Call<SeriesInfoResponse>, response: Response<SeriesInfoResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        episodesBySeason = body.episodes ?: emptyMap()
                        sortedSeasons = episodesBySeason.keys.sortedBy { it.toIntOrNull() ?: 0 }
                        if (sortedSeasons.isNotEmpty()) mudarTemporada(sortedSeasons.first())
                        else btnSeasonSelector.text = "Indisponível"
                    }
                }
                override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                    Toast.makeText(this@SeriesDetailsActivity, "Erro de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun mostrarSeletorDeTemporada() {
        if (sortedSeasons.isEmpty()) return
        val nomes = sortedSeasons.map { "Temporada $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Escolha a Temporada")
            .setItems(nomes) { _, which -> mudarTemporada(sortedSeasons[which]) }
            .show()
    }

    private fun mudarTemporada(seasonKey: String) {
        currentSeason = seasonKey
        btnSeasonSelector.text = "Temporada $seasonKey ▼"
        val lista = episodesBySeason[seasonKey] ?: emptyList()
        if (lista.isNotEmpty()) {
            currentEpisode = lista.first()
            restaurarEstadoDownload()
        }
        rvEpisodes.adapter = EpisodeAdapter(lista) { ep, _ ->
            currentEpisode = ep
            restaurarEstadoDownload()
            abrirPlayer(ep, true)
        }
    }

    // ==========================================
    // AQUI ESTÁ A CORREÇÃO: "A MOCHILA"
    // ==========================================
    private fun abrirPlayer(ep: EpisodeStream, usarResume: Boolean) {
        val streamId = ep.id.toIntOrNull() ?: 0
        val ext = ep.container_extension ?: "mp4"

        val lista = episodesBySeason[currentSeason] ?: emptyList()
        val position = lista.indexOfFirst { it.id == ep.id }
        
        // Dados para o próximo imediato (legado)
        val nextEp = if (position + 1 < lista.size) lista[position + 1] else null
        val nextStreamId = nextEp?.id?.toIntOrNull() ?: 0
        val nextChannelName = nextEp?.let { "T${currentSeason}E${it.episode_num} - $seriesName" }

        // 🎒 CRIAÇÃO DA MOCHILA (LISTA COMPLETA DE IDs)
        // Isso garante que o player saiba TODOS os episódios futuros
        val mochilaIds = ArrayList<Int>()
        for (item in lista) {
            val idInt = item.id.toIntOrNull() ?: 0
            if (idInt != 0) mochilaIds.add(idInt)
        }

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val keyBase = "series_resume_$streamId"
        val pos = prefs.getLong("${keyBase}_pos", 0L)
        val dur = prefs.getLong("${keyBase}_dur", 0L)
        val existe = usarResume && pos > 30_000L && dur > 0L && pos < (dur * 0.95).toLong()

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", streamId)
        intent.putExtra("stream_ext", ext)
        intent.putExtra("stream_type", "series")
        intent.putExtra("channel_name", "T${currentSeason}E${ep.episode_num} - $seriesName")
        
        // Passa a Mochila
        if (mochilaIds.isNotEmpty()) {
            intent.putIntegerArrayListExtra("episode_list", mochilaIds)
        }

        if (existe) intent.putExtra("start_position_ms", pos)
        
        if (nextStreamId != 0) {
            intent.putExtra("next_stream_id", nextStreamId)
            if (nextChannelName != null) intent.putExtra("next_channel_name", nextChannelName)
        }
        
        startActivity(intent)
    }

    private fun montarUrlEpisodio(ep: EpisodeStream): String {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        val serverList = listOf("http://tvblack.shop", "http://firewallnaousardns.xyz:80", "http://fibercdn.sbs")
        val server = serverList.first()

        val eid = ep.id.toIntOrNull() ?: 0
        val ext = ep.container_extension ?: "mp4"

        return montarUrlStream(server, "series", user, pass, eid, ext)
    }

    // Função auxiliar que faltava
    private fun montarUrlStream(server: String, streamType: String, user: String, pass: String, id: Int, ext: String): String {
        var base = if (server.endsWith("/")) server.dropLast(1) else server
        if (!base.startsWith("http")) base = "http://$base"
        val output = if (ext.isEmpty()) "ts" else ext
        return "$base/get.php?username=$user&password=$pass&type=$streamType&output=$output&id=$id"
    }

    private fun baixarTemporadaAtual(lista: List<EpisodeStream>) {
        var count = 0
        for (ep in lista) {
            val eid = ep.id.toIntOrNull() ?: continue
            val url = montarUrlEpisodio(ep)
            val safeTitle = seriesName.replace("[^a-zA-Z0-9 _.-]".toRegex(), "_").ifBlank { "serie" }
            val fileName = "${safeTitle}_T${currentSeason}E${ep.episode_num}_${eid}.mp4"
            DownloadHelper.enqueueDownload(this, url, fileName, logicalId = "series_$eid", type = "series")
            count++
        }
        Toast.makeText(this, "Baixando $count episódios...", Toast.LENGTH_LONG).show()
        currentEpisode?.let { setDownloadState(DownloadState.BAIXANDO, it) }
    }

    private fun getProgressText(): String {
        val ep = currentEpisode ?: return "Baixar"
        val eid = ep.id.toIntOrNull() ?: 0
        if (eid == 0) return "Baixar"
        val progress = DownloadHelper.getDownloadProgress(this, "series_$eid")
        return when (downloadState) {
            DownloadState.BAIXAR -> "Baixar"
            DownloadState.BAIXANDO -> "Baixando ${progress}%"
            DownloadState.BAIXADO -> "Baixado"
        }
    }

    private fun setDownloadState(state: DownloadState, ep: EpisodeStream?) {
        downloadState = state
        val eid = ep?.id?.toIntOrNull() ?: currentEpisode?.id?.toIntOrNull() ?: 0
        if (eid != 0) {
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("series_download_state_$eid", state.name).apply()
        }
        when (state) {
            DownloadState.BAIXAR -> {
                imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_arrow)
                tvDownloadEpisodeState.text = getProgressText()
            }
            DownloadState.BAIXANDO -> {
                imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_loading)
                tvDownloadEpisodeState.text = getProgressText()
            }
            DownloadState.BAIXADO -> {
                imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_done)
                tvDownloadEpisodeState.text = getProgressText()
            }
        }
    }

    private fun restaurarEstadoDownload() {
        val ep = currentEpisode ?: run {
            downloadState = DownloadState.BAIXAR
            imgDownloadEpisodeState.setImageResource(R.drawable.ic_dl_arrow)
            tvDownloadEpisodeState.text = "Baixar"
            return
        }
        val eid = ep.id.toIntOrNull() ?: 0
        if (eid == 0) {
            setDownloadState(DownloadState.BAIXAR, ep)
            return
        }
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("series_download_state_$eid", DownloadState.BAIXAR.name)
        val state = try { DownloadState.valueOf(saved ?: DownloadState.BAIXAR.name) } catch (_: Exception) { DownloadState.BAIXAR }
        setDownloadState(state, ep)
    }

    class EpisodeAdapter(val list: List<EpisodeStream>, private val onClick: (EpisodeStream, Int) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvEpisodeTitle)
            val imgThumb: ImageView? = v.findViewById(R.id.imgEpisodeThumb)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = list[position]
            holder.tvTitle.text = "E${ep.episode_num.toString().padStart(2, '0')} - ${ep.title}"
            
            if (holder.imgThumb != null) {
                Glide.with(holder.itemView.context).load(ep.info?.movie_image)
                    .placeholder(android.R.drawable.ic_menu_gallery).error(android.R.color.darker_gray).centerCrop().into(holder.imgThumb)
            }
            holder.itemView.setOnClickListener { onClick(ep, position) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.tvTitle.setTextColor(Color.YELLOW)
                    holder.tvTitle.setBackgroundColor(Color.parseColor("#CC000000"))
                } else {
                    holder.tvTitle.setTextColor(Color.WHITE)
                    holder.tvTitle.setBackgroundColor(Color.parseColor("#D9000000"))
                }
            }
        }
        override fun getItemCount() = list.size
    }
}
