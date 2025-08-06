package com.example.howl

import android.util.Log
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable

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

object RemoteControlServer {
    const val SERVER_PORT = 4695
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(port: Int = SERVER_PORT) {
        if (server != null) return
        HLog.d("RemoteControlServer","Starting remote control server")

        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json()
            }
            routing {
                post("/start_player") {
                    val body = call.receive<StartPlayerRequest>()
                    val from = body.from
                    HLog.d("RemoteControlServer", "Received command start_player($from)")
                    Player.startPlayer(from)
                    call.respond(HttpStatusCode.OK, "started")
                }
                post("/seek") {
                    val body = call.receive<SeekRequest>()
                    val position = body.position
                    HLog.d("RemoteControlServer", "Received command seek($position)")
                    Player.seek(position)
                    call.respond(HttpStatusCode.OK, "seek")
                }
                post("/stop_player") {
                    call.receive<StopPlayerRequest>()
                    HLog.d("RemoteControlServer", "Received command stop_player")
                    Player.stopPlayer()
                    call.respond(HttpStatusCode.OK, "stopped")
                }
                post("/load_funscript") {
                    val body = call.receive<LoadFunscriptRequest>()
                    val title = body.title
                    val funscript = body.funscript
                    HLog.d("RemoteControlServer", "Received command load_funscript(title='$title', funscript length=${funscript.length})")
                    try {
                        val pulseSource = FunscriptPulseSource()
                        pulseSource.loadFromString(funscript, title)
                        Player.switchPulseSource(pulseSource)
                        call.respond(HttpStatusCode.OK, "funscript loaded")
                    }
                    catch (_: BadFileException) {
                        HLog.d("RemoteControlServer", "Failed to load funscript: invalid funscript file")
                        call.respond(HttpStatusCode.BadRequest, "Invalid funscript file")
                    } catch (e: Exception) {
                        HLog.d("RemoteControlServer", "Unexpected error loading funscript", e)
                        call.respond(HttpStatusCode.InternalServerError, "Internal server error")
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