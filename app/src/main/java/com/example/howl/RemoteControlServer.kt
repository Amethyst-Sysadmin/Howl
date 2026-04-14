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
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Base64
import kotlin.Int
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
data class StartPlayerRequest(val from: Float? = null)

@Serializable
data class SeekRequest(val position: Float)

@Serializable
data class LoadFunscriptRequest(
    val title: String = "",
    val loop: Boolean = false,
    val play: Boolean = false,
    val funscript: String
)

@Serializable
data class LoadHwlRequest(
    val title: String = "",
    val loop: Boolean = true,
    val play: Boolean = false,
    val hwl: String
)

@Serializable
data class SetPowerRequest(
    val power_a: Int? = null,
    val power_b: Int? = null
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

@Serializable
data class SetMuteRequest(
    val value: Boolean? = null,
)

@Serializable
data class SetAutoIncreaseRequest(
    val value: Boolean? = null,
)

@Serializable
data class SetSwapChannelsRequest(
    val value: Boolean? = null,
)

@Serializable
data class SetFreqRangeRequest(
    val min: Float,
    val max: Float,
)

@Serializable
data class LoadActivityRequest(
    val name: String,
    val play: Boolean = false,
)

@Serializable
data class ActivityInfo(
    val name: String,
    val display_name: String
)

@Serializable
data class AvailableActivitiesResponse(
    val activities: List<ActivityInfo>
)

@Serializable
data class MainOptionsStatusResponse(
    val power_a: Int,
    val power_b: Int,
    val power_a_limit: Int,
    val power_b_limit: Int,
    val mute: Boolean,
    val auto_increase_power: Boolean,
    val swap_channels: Boolean,
    val freq_range_min: Float,
    val freq_range_max: Float
)

@Serializable
data class PlayerStatusResponse(
    val playing: Boolean,
    val position: Float,
    val title: String,
    val duration: Float
)

@Serializable
data class StatusResponse(
    val options: MainOptionsStatusResponse,
    val player: PlayerStatusResponse
)

@Serializable
data class ErrorResponse(
    val error: ErrorBody
)

@Serializable
data class ErrorBody(
    val message: String
)

object RemoteControlServer {
    const val SERVER_PORT = 4695
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun initialise() {
        if (Prefs.remoteAPIKey.value == "changeme") {
            Prefs.remoteAPIKey.value = generateApiKey()
            Prefs.remoteAPIKey.save()
        }

        if (Prefs.remoteAccess.value) {
            start()
        }
    }

    fun buildStatusResponse(): StatusResponse {
        val options = MainOptions.state.value
        val playerState = Player.playerState.value
        val playerPosition = Player.playerPosition.value

        val optionsStatus = MainOptionsStatusResponse(
            power_a = options.channelAPower,
            power_b = options.channelBPower,
            power_a_limit = Prefs.powerLimitA.value,
            power_b_limit = Prefs.powerLimitB.value,
            mute = options.globalMute,
            auto_increase_power = options.autoIncreasePower,
            swap_channels = options.swapChannels,
            freq_range_min = options.frequencyRangeSelectedSubset.start,
            freq_range_max = options.frequencyRangeSelectedSubset.endInclusive
        )

        val playerStatus = PlayerStatusResponse(
            playing = playerState.isPlaying,
            position = playerPosition.toFloat(),
            title = playerState.activePulseSource?.displayName?.value ?: "",
            duration = playerState.activePulseSource?.duration?.toFloat() ?: 0.0f,
        )

        return StatusResponse(
            options = optionsStatus,
            player = playerStatus
        )
    }

    fun start(port: Int = SERVER_PORT) {
        if (server != null) return
        HLog.d("RemoteControlServer","Starting remote control server")

        server = embeddedServer(CIO, port = port) {
            install(CORS) {
                // Allow requests from any host.
                // For production, you might want to restrict this to specific domains.
                anyHost()

                // Allow the Authorization header so browsers can send your API Key
                allowHeader(HttpHeaders.Authorization)

                // Allow Content-Type header for JSON bodies
                allowHeader(HttpHeaders.ContentType)

                // Required for Bearer Auth to work in browsers
                allowCredentials = true

                // Allow the HTTP methods used by your API
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Options)
            }
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
                    post("/status") {
                        //HLog.d("RemoteControlServer", "Received command status")
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/start_player") {
                        val body = call.receive<StartPlayerRequest>()
                        val from = body.from
                        HLog.d("RemoteControlServer", "Received command start_player($from)")
                        withContext(Dispatchers.Main) {
                            Player.startPlayer(from?.toDouble())
                        }

                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/seek") {
                        val body = call.receive<SeekRequest>()
                        val position = body.position
                        HLog.d("RemoteControlServer", "Received command seek($position)")
                        withContext(Dispatchers.Main) {
                            Player.seek(position.toDouble())
                        }
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/stop_player") {
                        HLog.d("RemoteControlServer", "Received command stop_player")
                        withContext(Dispatchers.Main) {
                            Player.stopPlayer()
                        }
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/load_funscript") {
                        val body = call.receive<LoadFunscriptRequest>()
                        val title = body.title
                        val loop = body.loop
                        val play = body.play
                        val funscript = body.funscript

                        HLog.d(
                            "RemoteControlServer",
                            "Received command load_funscript(title='$title', loop=$loop, play=$play, funscript length=${funscript.length})"
                        )
                        try {
                            val pulseSource = FunscriptPulseSource()
                            pulseSource.loadFromString(funscript, title)
                            withContext(Dispatchers.Main) {
                                Player.switchPulseSource(pulseSource)
                                if(play)
                                    Player.startPlayer()
                            }
                            val status = buildStatusResponse()
                            call.respond(HttpStatusCode.OK, status)
                        } catch (_: BadFileException) {
                            HLog.d(
                                "RemoteControlServer",
                                "Failed to load funscript: invalid funscript file"
                            )
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorBody("Invalid funscript file"))
                            )
                        } catch (e: Exception) {
                            HLog.d("RemoteControlServer", "Unexpected error loading funscript", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(ErrorBody("Internal server error"))
                            )
                        }
                    }
                    post("/load_hwl") {
                        val body = call.receive<LoadHwlRequest>()
                        val title = body.title
                        val loop = body.loop
                        val play = body.play
                        //Convert the Base64 String we receive back into a ByteArray
                        val hwlData = Base64.getDecoder().decode(body.hwl)

                        HLog.d(
                            "RemoteControlServer",
                            "Received command load_hwl(title='$title', loop=$loop, play=$play, hwl size=${hwlData.size} bytes)"
                        )

                        try {
                            val pulseSource = HWLPulseSource()
                            pulseSource.loadFromBytes(
                                data = hwlData,
                                title = title,
                                loop = loop
                            )

                            withContext(Dispatchers.Main) {
                                Player.switchPulseSource(pulseSource)
                                if(play)
                                    Player.startPlayer()
                            }

                            val status = buildStatusResponse()
                            call.respond(HttpStatusCode.OK, status)
                        } catch (_: BadFileException) {
                            HLog.d("RemoteControlServer", "Failed to load HWL: invalid HWL file")
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorBody("Invalid HWL file"))
                            )
                        } catch (e: Exception) {
                            HLog.d("RemoteControlServer", "Unexpected error loading HWL", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(ErrorBody("Internal server error"))
                            )
                        }
                    }
                    post("/set_power") {
                        val body = call.receive<SetPowerRequest>()
                        val powerA = body.power_a
                        val powerB = body.power_b

                        HLog.d(
                            "RemoteControlServer",
                            "Received command set_power(power_a=$powerA, power_b=$powerB)"
                        )
                        if (powerA != null)
                            MainOptions.setChannelPower(0, powerA)
                        if (powerB != null)
                            MainOptions.setChannelPower(1, powerB)

                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
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
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
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
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/set_mute") {
                        val body = call.receive<SetMuteRequest>()
                        val value = body.value

                        HLog.d(
                            "RemoteControlServer",
                            "Received command set_mute(value=$value)"
                        )

                        val newMuteState = value ?: !MainOptions.state.value.globalMute
                        MainOptions.setGlobalMute(newMuteState)
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/set_swap_channels") {
                        val body = call.receive<SetSwapChannelsRequest>()
                        val value = body.value

                        HLog.d(
                            "RemoteControlServer",
                            "Received command set_swap_channels(value=$value)"
                        )

                        val newSwapState = value ?: !MainOptions.state.value.swapChannels
                        MainOptions.setSwapChannels(newSwapState)
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/set_auto_increase") {
                        val body = call.receive<SetAutoIncreaseRequest>()
                        val value = body.value

                        HLog.d(
                            "RemoteControlServer",
                            "Received command set_auto_increase(value=$value)"
                        )

                        val newAutoIncreaseState = value ?: !MainOptions.state.value.autoIncreasePower
                        MainOptions.setAutoIncreasePower(newAutoIncreaseState)
                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/set_freq_range") {
                        val body = call.receive<SetFreqRangeRequest>()
                        val min = body.min
                        val max = body.max

                        HLog.d(
                            "RemoteControlServer",
                            "Received command set_auto_increase(min=$min max=$max)"
                        )

                        // Validation
                        when {
                            min !in 0.0..1.0 -> {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse(ErrorBody("min must be between 0.0 and 1.0"))
                                )
                                return@post
                            }
                            max !in 0.0..1.0 -> {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse(ErrorBody("max must be between 0.0 and 1.0"))
                                )
                                return@post
                            }
                            max <= min -> {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse(ErrorBody("max must be greater than min"))
                                )
                                return@post
                            }
                            (max - min) < 0.01 -> {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse(ErrorBody("min and max must differ by at least 0.01"))
                                )
                                return@post
                            }
                        }

                        val range = min..max
                        MainOptions.setFrequencyRangeSelectedSubset(range)

                        val status = buildStatusResponse()
                        call.respond(HttpStatusCode.OK, status)
                    }
                    post("/available_activities") {
                        HLog.d("RemoteControlServer", "Received command available_activities")
                        val activities = ActivityType.entries.map { type ->
                            ActivityInfo(
                                name = type.name,
                                display_name = type.displayName
                            )
                        }.sortedBy { it.display_name.lowercase() }
                        call.respond(HttpStatusCode.OK, AvailableActivitiesResponse(activities))
                    }
                    post("/load_activity") {
                        val body = call.receive<LoadActivityRequest>()
                        val name = body.name
                        val play = body.play
                        HLog.d("RemoteControlServer", "Received command load_activity(name='$name', play=$play)")

                        try {
                            val activityType = ActivityType.valueOf(name)
                            withContext(Dispatchers.Main) {
                                if (!play)
                                    Player.stopPlayer()
                                ActivityHost.setCurrentActivity(activityType)
                                Player.switchPulseSource(ActivityHost)
                                if (play)
                                    Player.startPlayer()
                            }
                            val status = buildStatusResponse()
                            call.respond(HttpStatusCode.OK, status)
                        } catch (_: IllegalArgumentException) {
                            HLog.d(
                                "RemoteControlServer",
                                "Failed to load activity: unknown activity name '$name'"
                            )
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorBody("Unknown activity: $name"))
                            )
                        }
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