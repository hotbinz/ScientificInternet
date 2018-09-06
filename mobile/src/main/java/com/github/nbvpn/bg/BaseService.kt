/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.nbvpn.bg

import android.annotation.TargetApi
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.support.v4.os.UserManagerCompat
import android.util.Base64
import android.util.Log
import com.github.nbvpn.App.Companion.app
import com.github.nbvpn.R
import com.github.nbvpn.acl.Acl
import com.github.nbvpn.acl.AclSyncJob
import com.github.nbvpn.aidl.IShadowsocksService
import com.github.nbvpn.aidl.IShadowsocksServiceCallback
import com.github.nbvpn.database.Profile
import com.github.nbvpn.database.ProfileManager
import com.github.nbvpn.plugin.PluginConfiguration
import com.github.nbvpn.plugin.PluginManager
import com.github.shadowsocks.plugin.PluginOptions
import com.github.nbvpn.preference.DataStore
import com.github.nbvpn.utils.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * This object uses WeakMap to simulate the effects of multi-inheritance.
 */
object BaseService {
    /**
     * IDLE state is only used by UI and will never be returned by BaseService.
     */
    const val IDLE = 0
    const val CONNECTING = 1
    const val CONNECTED = 2
    const val STOPPING = 3
    const val STOPPED = 4

    const val CONFIG_FILE = "shadowsocks.conf"

    const val SSS_CONFIG_FILE = "sss.conf"
    const val SSS_LOCAL_PROT = 8399
    const val SSS_LOCAL_PASSWORD = "HUOBIN1"


    class Data internal constructor(private val service: Interface) {
        @Volatile var profile: Profile? = null
        @Volatile var state = STOPPED
        @Volatile var plugin = PluginOptions()
        @Volatile var pluginPath: String? = null

        val processes = GuardedProcessPool()

        var timer: Timer? = null
        var trafficMonitorThread: TrafficMonitorThread? = null
        var loggerThread: UrlMonitorThread? = null

        val callbacks = RemoteCallbackList<IShadowsocksServiceCallback>()

        val bandwidthListeners = HashSet<IBinder>() // the binder is the real identifier

        var notification: ServiceNotification? = null
        val closeReceiver = broadcastReceiver { _, intent ->
            when (intent.action) {
                Action.RELOAD -> service.forceLoad()
                else -> service.stopRunner(true)
            }
        }
        var closeReceiverRegistered = false

        val binder = object : IShadowsocksService.Stub() {
            override fun getState(): Int = this@Data.state
            override fun getProfileName(): String = profile?.name ?: "Idle"

            override fun registerCallback(cb: IShadowsocksServiceCallback) {
                callbacks.register(cb)
            }

            override fun startListeningForBandwidth(cb: IShadowsocksServiceCallback) {
                if (bandwidthListeners.add(cb.asBinder())) {
                    if (timer == null) {
                        val t = Timer(true)
                        t.schedule(object : TimerTask() {
                            override fun run() {
                                if (state == CONNECTED && TrafficMonitor.updateRate()) app.handler.post {
                                    if (bandwidthListeners.isNotEmpty()) {
                                        val txRate = TrafficMonitor.txRate
                                        val rxRate = TrafficMonitor.rxRate
                                        val txTotal = TrafficMonitor.txTotal
                                        val rxTotal = TrafficMonitor.rxTotal
                                        val n = callbacks.beginBroadcast()
                                        for (i in 0 until n) try {
                                            val item = callbacks.getBroadcastItem(i)
                                            if (bandwidthListeners.contains(item.asBinder()))
                                                item.trafficUpdated(profile!!.id, txRate, rxRate, txTotal, rxTotal)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            app.track(e)
                                        }
                                        callbacks.finishBroadcast()
                                    }
                                }
                            }
                        }, 1000, 1000)
                        timer = t
                    }
                    TrafficMonitor.updateRate()
                    if (state == CONNECTED) cb.trafficUpdated(profile!!.id,
                            TrafficMonitor.txRate, TrafficMonitor.rxRate,
                            TrafficMonitor.txTotal, TrafficMonitor.rxTotal)
                }
            }

            override fun stopListeningForBandwidth(cb: IShadowsocksServiceCallback) {
                if (bandwidthListeners.remove(cb.asBinder()) && bandwidthListeners.isEmpty()) {
                    timer!!.cancel()
                    timer = null
                }
            }

            override fun unregisterCallback(cb: IShadowsocksServiceCallback) {
                stopListeningForBandwidth(cb)   // saves an RPC, and safer
                callbacks.unregister(cb)
            }
        }

        internal fun updateTrafficTotal(tx: Long, rx: Long) {
            try {
                // this.profile may have host, etc. modified and thus a re-fetch is necessary (possible race condition)
                val profile = ProfileManager.getProfile((profile ?: return).id) ?: return
                profile.tx += tx
                profile.rx += rx
                ProfileManager.updateProfile(profile)
                app.handler.post {
                    if (bandwidthListeners.isNotEmpty()) {
                        val n = callbacks.beginBroadcast()
                        for (i in 0 until n) {
                            try {
                                val item = callbacks.getBroadcastItem(i)
                                if (bandwidthListeners.contains(item.asBinder())) item.trafficPersisted(profile.id)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                app.track(e)
                            }
                        }
                        callbacks.finishBroadcast()
                    }
                }
            } catch (e: IOException) {
                if (!DataStore.directBootAware) throw e // we should only reach here because we're in direct boot
                val profile = DirectBoot.getDeviceProfile()!!
                profile.tx += tx
                profile.rx += rx
                profile.dirty = true
                DirectBoot.update(profile)
                DirectBoot.listenForUnlock()
            }
        }



        internal var shadowsocksConfigFile: File? = null
        internal fun buildShadowsocksConfig(): File {
            val profile = profile!!
            val config = JSONObject()
                    .put("server", profile.host)
                    .put("server_port", profile.remotePort)
                    .put("method", profile.method)

            if (profile.proxyType == "socks5") {
                config.put("password", SSS_LOCAL_PASSWORD)
            }
            else {
                config.put("password", profile.password)
            }
            val pluginPath = pluginPath
            if (profile.proxyType != "shadowsocks") {
                val pluginCmd = arrayListOf(File((service as Context).applicationInfo.nativeLibraryDir, Executable.SSS_PROXY).absolutePath)
                pluginCmd.add("-c")
                pluginCmd.add(buildSSSConfig().absolutePath)
                pluginCmd.add("-d")
                config
                        .put("plugin", Commandline.toString(service.buildAdditionalArguments(pluginCmd)))
                        .put("plugin_opts", profile.proxyType)
            }
            else if (pluginPath != null) {
                val pluginCmd = arrayListOf(pluginPath)
                if (TcpFastOpen.sendEnabled) pluginCmd.add("--fast-open")
                config
                        .put("plugin", Commandline.toString(service.buildAdditionalArguments(pluginCmd)))
                        .put("plugin_opts", plugin.toString())
            }
            // sensitive Shadowsocks config is stored in
            val file = File(if (UserManagerCompat.isUserUnlocked(app)) app.filesDir else @TargetApi(24) {
                app.deviceContext.noBackupFilesDir  // only API 24+ will be in locked state
            }, CONFIG_FILE)
            shadowsocksConfigFile = file
            file.writeText(config.toString())
            return file
        }

        internal fun buildSSSConfig(): File {
            val profile = profile!!
            val config = JSONObject()
                    .put("server_port", SSS_LOCAL_PROT)
                    .put("proxy_url", "${profile.proxyType}://${profile.host}:${profile.remotePort}")
                    .put("password", SSS_LOCAL_PASSWORD)

            // sensitive Shadowsocks config is stored in
            val file = File(if (UserManagerCompat.isUserUnlocked(app)) app.filesDir else @TargetApi(24) {
                app.deviceContext.noBackupFilesDir  // only API 24+ will be in locked state
            }, SSS_CONFIG_FILE)
            file.writeText(config.toString())
            return file
        }

        val aclFile: File? get() {
            val route = profile!!.route
            return if (route == Acl.ALL) null else Acl.getFile(route)
        }

        fun changeState(s: Int, msg: String? = null) {
            if (state == s && msg == null) return
            if (callbacks.registeredCallbackCount > 0) app.handler.post {
                val n = callbacks.beginBroadcast()
                for (i in 0 until n) try {
                    callbacks.getBroadcastItem(i).stateChanged(s, binder.profileName, msg)
                } catch (e: Exception) {
                    e.printStackTrace()
                    app.track(e)
                }
                callbacks.finishBroadcast()
            }
            state = s
        }
    }
    interface Interface {
        val tag: String

        fun onBind(intent: Intent): IBinder? = if (intent.action == Action.SERVICE) data.binder else null

        fun checkProfile(profile: Profile): Boolean =
                if (profile.host.isEmpty() || profile.password.isEmpty()) {
                    stopRunner(true, (this as Context).getString(R.string.proxy_empty))
                    false
                } else true
        fun forceLoad() {
            val profile = app.currentProfile
                    ?: return stopRunner(true, (this as Context).getString(R.string.profile_empty))
            if (!checkProfile(profile)) return
            val s = data.state
            when (s) {
                STOPPED -> startRunner()
                CONNECTED -> {
                    stopRunner(false)
                    startRunner()
                }
                else -> Log.w(tag, "Illegal state when invoking use: $s")
            }
        }

        fun buildAdditionalArguments(cmd: ArrayList<String>): ArrayList<String> = cmd

        fun startNativeProcesses() {
            val data = data
            val profile = data.profile!!
            val cmd = buildAdditionalArguments(arrayListOf(
                    File((this as Context).applicationInfo.nativeLibraryDir, Executable.SS_LOCAL).absolutePath,
                    "-u",
                    "-b", "127.0.0.1",
                    "-l", DataStore.portProxy.toString(),
                    "-t", "600",
                    "-v", "1",
                    "-c", data.buildShadowsocksConfig().absolutePath))

            val acl = data.aclFile
            if (acl != null) {
                cmd += "--acl"
                cmd += acl.absolutePath
            }

            if (profile.udpdns) cmd += "-D"

            if (TcpFastOpen.sendEnabled) cmd += "--fast-open"

            if (profile.proxyType != "shadowsocks") {
                cmd += "--proxyprot"
                cmd += SSS_LOCAL_PROT.toString()
            }
            data.processes.start(cmd)
        }

        fun createNotification(profileName: String): ServiceNotification

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        fun killProcesses() {
            data.processes.killAll()
//            data.sss_processes.killAll()
        }

        fun stopRunner(stopService: Boolean, msg: String? = null) {
            // channge the state
            val data = data
            data.changeState(STOPPING)

            app.track(tag, "stop")

            killProcesses()

            // clean up recevier
            this as Service
            if (data.closeReceiverRegistered) {
                unregisterReceiver(data.closeReceiver)
                data.closeReceiverRegistered = false
            }

            data.shadowsocksConfigFile?.delete()    // remove old config possibly in device storage
            data.shadowsocksConfigFile = null

            data.notification?.destroy()
            data.notification = null

            // Make sure update total traffic when stopping the runner
            data.updateTrafficTotal(TrafficMonitor.txTotal, TrafficMonitor.rxTotal)

            TrafficMonitor.reset()
            data.trafficMonitorThread?.stopThread()
            data.trafficMonitorThread = null

            data.loggerThread?.stopThread()
            data.loggerThread = null

            // change the state
            data.changeState(STOPPED, msg)

            // stop the service if nothing has bound to it
            if (stopService) stopSelf()

            data.profile = null
        }

        val data: Data get() = instances[this]!!

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val data = data
            if (data.state != STOPPED) return Service.START_NOT_STICKY
            val profile = app.currentProfile
            this as Context
            if (profile == null) {
                data.notification = createNotification("")  // gracefully shutdown: https://stackoverflow.com/questions/47337857/context-startforegroundservice-did-not-then-call-service-startforeground-eve
                stopRunner(true, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }
            profile.name = profile.formattedName    // save name for later queries
            data.profile = profile
            //流量统计
            TrafficMonitor.reset()
            val thread = TrafficMonitorThread()
            thread.start()
            data.trafficMonitorThread = thread
            //访问日志
            val loggerThread = UrlMonitorThread()
            loggerThread.start()
            data.loggerThread = loggerThread

            if (!data.closeReceiverRegistered) {
                // register close receiver
                val filter = IntentFilter()
                filter.addAction(Action.RELOAD)
                filter.addAction(Intent.ACTION_SHUTDOWN)
                filter.addAction(Action.CLOSE)
                registerReceiver(data.closeReceiver, filter)
                data.closeReceiverRegistered = true
            }

            data.notification = createNotification(profile.formattedName)
            app.track(tag, "start")

            data.changeState(CONNECTING)

            thread("$tag-Connecting") {
                try {
//                    if (profile.host == "198.199.101.152") {
//                        val client = OkHttpClient.Builder()
//                                .dns {
//                                    listOf((Dns.resolve(it, false) ?: throw UnknownHostException())
//                                            .parseNumericAddress())
//                                }
//                                .connectTimeout(10, TimeUnit.SECONDS)
//                                .writeTimeout(10, TimeUnit.SECONDS)
//                                .readTimeout(30, TimeUnit.SECONDS)
//                                .build()
//                        val mdg = MessageDigest.getInstance("SHA-1")
//                        mdg.update(app.info.signatures[0].toByteArray())
//                        val requestBody = FormBody.Builder()
//                                .add("sig", String(Base64.encode(mdg.digest(), 0)))
//                                .build()
//                        val request = Request.Builder()
//                                .url(app.remoteConfig.getString("proxy_url"))
//                                .post(requestBody)
//                                .build()
//
//                        val proxies = client.newCall(request).execute()
//                                .body()!!.string().split('|').toMutableList()
//                        proxies.shuffle()
//                        val proxy = proxies.first().split(':')
//                        profile.host = proxy[0].trim()
//                        profile.remotePort = proxy[1].trim().toInt()
//                        profile.password = proxy[2].trim()
//                        profile.method = proxy[3].trim()
//                    }

                    if (profile.route == Acl.CUSTOM_RULES)
                        Acl.save(Acl.CUSTOM_RULES, Acl.customRules.flatten(10))

                    data.plugin = PluginConfiguration(profile.plugin ?: "").selectedOptions
                    data.pluginPath = PluginManager.init(data.plugin)

                    // Clean up
                    killProcesses()

                    if (!profile.host.isNumericAddress())
                        profile.host = Dns.resolve(profile.host, true) ?: throw UnknownHostException()

                    startNativeProcesses()

                    if (profile.route !in arrayOf(Acl.ALL, Acl.CUSTOM_RULES)) AclSyncJob.schedule(profile.route)

                    data.changeState(CONNECTED)
                } catch (_: UnknownHostException) {
                    stopRunner(true, getString(R.string.invalid_server))
                } catch (_: VpnService.NullConnectionException) {
                    stopRunner(true, getString(R.string.reboot_required))
                } catch (exc: Throwable) {
                    stopRunner(true, "${getString(R.string.service_failed)}: ${exc.message}")
                    app.track(exc)
                }
            }
            return Service.START_NOT_STICKY
        }
    }

    private val instances = WeakHashMap<Interface, Data>()
    internal fun register(instance: Interface) = instances.put(instance, Data(instance))

    val usingVpnMode: Boolean get() = DataStore.serviceMode == Key.modeVpn
    val serviceClass: KClass<out Any> get() = when (DataStore.serviceMode) {
        Key.modeProxy -> ProxyService::class
        Key.modeVpn -> VpnService::class
        Key.modeTransproxy -> TransproxyService::class
        else -> throw UnknownError()
    }
}
