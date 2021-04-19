package com.playgame.online

import android.app.Application
import android.content.Intent
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.AppsFlyerLibCore
import com.onesignal.OneSignal
import org.json.JSONException

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        OneSignal.setLogLevel(OneSignal.LOG_LEVEL.VERBOSE, OneSignal.LOG_LEVEL.NONE);
        OneSignal.initWithContext(this)
        OneSignal.setAppId("7e5be357-7543-4530-a375-a540e5eef7a3")

        // Если пользователь нажимает на уведомление, содержащее ссылку
        // передаём в активити с вебвью интент с данной ссылкой (в активити обязаны обработать в методе onNewIntent!!!)

        OneSignal.setNotificationOpenedHandler { result ->
            result.notification.additionalData?.let { additionalData ->
                if (additionalData.has("url")) {
                    val url = additionalData.getString("url")
                    Intent(this, MainActivity::class.java).also {
                        it.putExtra("notification_url", url.toString())
                        it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(it)
                    }

                    // Отправка параметров из уведомления в OneSignal
                    val keys = additionalData.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        try {
                            OneSignal.sendTag(key, additionalData.get(key).toString())
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        val devKey = "PvZBApFs2NkW7GcGoqvN9m" // ЗДЕСЬ ДОЛЖЕН БЫТЬ ВАШ КЛЮЧ, ПОЛУЧЕННЫЙ ИЗ APPSFLYER !!!
        val conversionDataListener  = object : AppsFlyerConversionListener {
            override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
                data?.let { cvData ->
                    cvData.map {
                        Log.i(AppsFlyerLibCore.LOG_TAG, "conversion_attribute:  ${it.key} = ${it.value}")
                    }
                }
            }

            override fun onConversionDataFail(error: String?) {
                Log.e(AppsFlyerLibCore.LOG_TAG, "error onAttributionFailure :  $error")
            }

            override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
                data?.map {
                    Log.d(AppsFlyerLibCore.LOG_TAG, "onAppOpen_attribute: ${it.key} = ${it.value}")
                }
            }

            override fun onAttributionFailure(error: String?) {
                Log.e(AppsFlyerLibCore.LOG_TAG, "error onAttributionFailure :  $error")
            }
        }

        AppsFlyerLib.getInstance().init(devKey, conversionDataListener, this)
        AppsFlyerLib.getInstance().startTracking(this)
    }
}