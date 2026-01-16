package com.example.howl

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Base64
import kotlin.random.Random

const val CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

fun generateApiKey(length: Int = 12): String {
    return buildString(length) {
        repeat(length) {
            append(CROCKFORD_BASE32[Random.nextInt(CROCKFORD_BASE32.length)])
        }
    }
}

@Serializable
data class StartPlayerRequest(val from: Double? = null)

@Serializable
data class SeekRequest(val position: Double)

@Serializable
object StopPlayerRequest // empty JSON object, just for consistency

@Serializable
data class LoadFunscriptRequest(
    val title: String = "",
    val funscript: String
)

@Serializable
data class LoadHwlRequest(
    val title: String = "",
    val hwl: String
)

@Serializable
data class SetPowerRequest(
    val channel: Int,
    val power: Int
)

@Serializable
data class IncrementPowerRequest(
    val channel: Int,
    val step: Int = 0
)

@Serializable
data class DecrementPowerRequest(
    val channel: Int,
    val step: Int = 0
)

object RemoteControlServer {
    const val SERVER_PORT = 4695
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private val uiScope = CoroutineScope(Dispatchers.Main)

    fun initialise() {
        if (Prefs.remoteAPIKey.value == "changeme") {
            Prefs.remoteAPIKey.value = generateApiKey()
            Prefs.remoteAPIKey.save()
        }

        if (Prefs.remoteAccess.value) {
            start()
        }
    }

    fun start(port: Int = SERVER_PORT) {
        if (server != null) return
        HLog.d("RemoteControlServer","Starting remote control server")

        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json()
            }
            install(Authentication) {
                bearer("auth-bearer") {
                    realm = "Howl Remote Server"
                    authenticate { tokenCredential ->
                        if (tokenCredential.token == Prefs.remoteAPIKey.value) {
                            UserIdPrincipal("api_user")
                        } else {
                            null // authentication failed
                        }
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    post("/start_player") {
                        val body = call.receive<StartPlayerRequest>()
                        val from = body.from
                        HLog.d("RemoteControlServer", "Received command start_player($from)")
                        uiScope.launch {
                            Player.startPlayer(from)
                        }
                        call.respond(HttpStatusCode.OK, "started")
                    }
                    post("/seek") {
                        val body = call.receive<SeekRequest>()
                        val position = body.position
                        HLog.d("RemoteControlServer", "Received command seek($position)")
                        uiScope.launch {
                            Player.seek(position)
                        }
                        call.respond(HttpStatusCode.OK, "seek")
                    }
                    post("/stop_player") {
                        call.receive<StopPlayerRequest>()
                        HLog.d("RemoteControlServer", "Received command stop_player")
                        uiScope.launch {
                            Player.stopPlayer()
                        }
                        call.respond(HttpStatusCode.OK, "stopped")
                    }
                    post("/load_funscript") {
                        val body = call.receive<LoadFunscriptRequest>()
                        val title = body.title
                        val funscript = body.funscript
                        HLog.d(
                            "RemoteControlServer",
                            "Received command load_funscript(title='$title', funscript length=${funscript.length})"
                        )
                        try {
                            val pulseSource = FunscriptPulseSource()
                            pulseSource.loadFromString(funscript, title)
                            uiScope.launch {
                                Player.switchPulseSource(pulseSource)
                            }
                            call.respond(HttpStatusCode.OK, "funscript loaded")
                        } catch (_: BadFileException) {
                            HLog.d(
                                "RemoteControlServer",
                                "Failed to load funscript: invalid funscript file"
                            )
                            call.respond(HttpStatusCode.BadRequest, "Invalid funscript file")
                        } catch (e: Exception) {
                            HLog.d("RemoteControlServer", "Unexpected error loading funscript", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                "Internal server error"
                            )
                        }
                    }
                    post("/load_hwl") {
                        val body = call.receive<LoadHwlRequest>()
                        val title = body.title
                        //Convert the Base64 String we receive back into a ByteArray
                        val hwlData = Base64.getDecoder().decode(body.hwl)

                        HLog.d(
                            "RemoteControlServer",
                            "Received command load_hwl(title='$title', hwl size=${hwlData.size} bytes)"
                        )

                        try {
                            val pulseSource = HWLPulseSource()
                            pulseSource.loadFromBytes(
                                data = hwlData,
                                title = body.title
                            )

                            uiScope.launch {
                                Player.switchPulseSource(pulseSource)
                            }

                            call.respond(HttpStatusCode.OK, "HWL loaded")
                        } catch (_: BadFileException) {
                            HLog.d("RemoteControlServer", "Failed to load HWL: invalid HWL file")
                            call.respond(HttpStatusCode.BadRequest, "Invalid HWL file")
                        } catch (e: Exception) {
                            HLog.d("RemoteControlServer", "Unexpected error loading HWL", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                "Internal server error"
                            )
                        }
                    }
                    post("/set_power") {
                        val body = call.receive<SetPowerRequest>()
                        val channel = body.channel
                        val power = body.power

                        HLog.d(
                            "RemoteControlServer",
                            "Received command set_power(channel=$channel, power=$power)"
                        )
                        MainOptions.setChannelPower(channel, power)
                        call.respond(HttpStatusCode.OK, "Power set")
                    }
                    post("/increment_power") {
                        val body = call.receive<IncrementPowerRequest>()
                        val channel = body.channel
                        val step = body.step

                        HLog.d(
                            "RemoteControlServer",
                            "Received command increment_power(channel=$channel, step=$step)"
                        )
                        MainOptions.incrementChannelPower(channel, step)
                        call.respond(HttpStatusCode.OK, "Power incremented")
                    }
                    post("/decrement_power") {
                        val body = call.receive<DecrementPowerRequest>()
                        val channel = body.channel
                        val step = body.step

                        HLog.d(
                            "RemoteControlServer",
                            "Received command decrement_power(channel=$channel, step=$step)"
                        )
                        MainOptions.decrementChannelPower(channel, step)
                        call.respond(HttpStatusCode.OK, "Power decremented")
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    fun stop() {
        HLog.d("RemoteControlServer", "Stopping remote control server")
        server?.stop(1000, 2000)
        server = null
    }
}