package dev.proxyserver

import android.net.LocalServerSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class Config(
    var listenType: String,
    var listenPort: Int,
    var listenUnixSocket: String,
    var maxIdleTimeout: Duration = 30.seconds
)


sealed class Address {
    class IPv4(val address: Inet4Address) : Address()
    class FQDN(val hostname: String) : Address()
    class IPv6(val address: Inet6Address) : Address()

    companion object {
        fun fromStream(input: InputStream): Address {
            val atyp = input.read().toByte()
            val address = when (atyp) {
                Socks5Server.ATYP_IPV4 -> IPv4(InetAddress.getByAddress(ByteArray(4).apply { input.read(this) }) as Inet4Address)
                Socks5Server.ATYP_FQDN -> FQDN(ByteArray(input.read()).apply { input.read(this) }.decodeToString())
                Socks5Server.ATYP_IPV6 -> IPv6(InetAddress.getByAddress(ByteArray(16).apply { input.read(this) }) as Inet6Address)
                else -> throw Error("Unknown addressType: $atyp")
            }
            return address
        }
    }

    fun toByteArray(): ByteArray = when (this) {
        is IPv4 -> ByteArray(1 + 4).also {
            it[0] = Socks5Server.ATYP_IPV4
            address.address.copyInto(it, 1)
        }

        is FQDN -> hostname.toByteArray().let { hostname ->
            ByteArray(1 + 1 + hostname.size).also {
                it[0] = Socks5Server.ATYP_FQDN
                it[1] = hostname.size.toByte()
                hostname.copyInto(it, 2)
            }
        }

        is IPv6 -> ByteArray(1 + 16).also {
            it[0] = Socks5Server.ATYP_IPV6
            address.address.copyInto(it, 1)
        }
    }

    override fun toString(): String {
        return when (this) {
            is IPv4 -> address.hostAddress
            is FQDN -> hostname
            is IPv6 -> "[${address.hostAddress}]"
        }
    }

}


class Socks5Server(val config: Config) {
    companion object {
        const val VER: Byte = 0x05

        const val CMD_CONNECT: Byte = 0x01
        const val CMD_BIND: Byte = 0x02
        const val CMD_UDP_ASSOCIATE: Byte = 0x03

        const val METHOD_NO_AUTHENTICATION_REQUIRED: Byte = 0x00
        const val METHOD_GSSAPI: Byte = 0x01
        const val METHOD_USERNAME_PASSWORD: Byte = 0x02
        const val METHOD_NO_ACCEPTABLE_METHODS: Byte = 0xFF.toByte()

        const val ATYP_IPV4: Byte = 0x01
        const val ATYP_FQDN: Byte = 0x03
        const val ATYP_IPV6: Byte = 0x04

        const val REP_SUCCEEDED: Byte = 0x00
        const val REP_GENERAL_FAILURE: Byte = 0x01
        const val REP_CONNECTION_NOT_ALLOWED: Byte = 0x02
        const val REP_NETWORK_UNREACHABLE: Byte = 0x03
        const val REP_HOST_UNREACHABLE: Byte = 0x04
        const val REP_CONNECTION_REFUSED: Byte = 0x05
        const val REP_TTL_EXPIRED: Byte = 0x06
        const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 0x08
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var serverSocket: MyServerSocket

    var onStarted: (() -> Unit)? = null
    var onStopped: (() -> Unit)? = null
    var logger: (String) -> Unit = {}

    fun start() {
        if (config.listenType == "port") {
            serverSocket = MyServerSocketFactory.wrap(ServerSocket(config.listenPort))
            logger("listening on :${config.listenPort}")
        } else {
            serverSocket = MyServerSocketFactory.wrap(LocalServerSocket(config.listenUnixSocket))
            logger("listening on @${config.listenUnixSocket}")
        }
        logger("service started")
        onStarted?.invoke()

        scope.launch {
            listenUntilStopped()
        }
    }

    fun listenUntilStopped() {
        while (true) {
            val clientSocket = runCatching { serverSocket.accept() }.getOrNull()
            if (clientSocket == null) {
                break
            }
            scope.launch {
                clientSocket.use {
                    handleConnection(clientSocket)
                }
            }
        }
        logger("service stopped")
        onStopped?.invoke()
    }


    fun stop() {
        serverSocket.close()
    }

    private suspend fun handleConnection(clientSocket: ClientSocket) {
        // https://www.rfc-editor.org/rfc/rfc1928
        val input = clientSocket.inputStream
        val output = clientSocket.outputStream
        val remoteAddress = clientSocket.remoteAddress

        val (target, port) = runCatching {
            // 1. 协议版本协商
            handleSocks(input, output)
            // 2. 处理请求, 读取目标地址
            handleRequest(input, output)
        }.fold(
            onSuccess = { it },
            onFailure = {
                logger("$remoteAddress $it")
                return
            }
        )
        logger("$remoteAddress -> $target:$port")
        // 3. 连接目标服务器
        val targetSocket: Socket = runCatching {
            when (target) {
                is Address.IPv4 -> Socket(target.address, port)
                is Address.FQDN -> Socket(target.hostname, port)
                is Address.IPv6 -> Socket(target.address, port)
            }
        }.fold(
            onSuccess = {
                output.write(byteArrayOf(VER, REP_SUCCEEDED, 0x00))
                output.write(target.toByteArray())
                output.write(ByteBuffer.allocate(2).putShort(port.toShort()).array())
                it
            },
            onFailure = {
                logger(it.message.toString())
                when (it) {
                    is java.net.ConnectException if it.message?.contains("Network is unreachable") == true -> {
                        output.write(byteArrayOf(VER, REP_NETWORK_UNREACHABLE, 0x00))
                    }

                    is java.net.UnknownHostException -> {
                        output.write(byteArrayOf(VER, REP_HOST_UNREACHABLE, 0x00))
                    }

                    is java.net.ConnectException if it.message?.contains("Connection refused") == true -> {
                        output.write(byteArrayOf(VER, REP_CONNECTION_REFUSED, 0x00))
                    }

                    else -> {
                        output.write(byteArrayOf(VER, REP_GENERAL_FAILURE, 0x00))
                    }
                }
                output.write(target.toByteArray())
                output.write(ByteBuffer.allocate(2).putShort(port.toShort()).array())
                return
            }
        )
        // 4. 流量转发
        targetSocket.use {
            val aliveChannel = Channel<Unit>(Channel.CONFLATED)
            val targetSocket = ClientSocketFactory.wrap(targetSocket)
            val job1 = scope.launch { handleStreamOneSide(clientSocket, targetSocket, aliveChannel) }
            val job2 = scope.launch { handleStreamOneSide(targetSocket, clientSocket, aliveChannel) }
            while (job1.isActive || job2.isActive) {
                if (withTimeoutOrNull(config.maxIdleTimeout) { aliveChannel.receive() } == null) {
                    logger("$remoteAddress connection timeout")
                    break
                }
            }
            aliveChannel.close()
        }
        logger("$remoteAddress -/-> $target:$port")
    }

    private fun handleSocks(input: InputStream, output: OutputStream) {
        val ver = input.read().toByte()
        if (ver != VER) {
            throw Error("Unsupported version")
        }
        val nMethods = input.read()
        val methods = ByteArray(nMethods).apply { input.read(this) }
        for (method in methods) {
            if (method == METHOD_NO_AUTHENTICATION_REQUIRED) {
                output.write(byteArrayOf(ver, method))
                return
            }
        }
        output.write(byteArrayOf(ver, METHOD_NO_ACCEPTABLE_METHODS))
        throw Error("Unsupported method")
    }

    private fun handleRequest(input: InputStream, output: OutputStream): Pair<Address, Int> {
        val (ver, cmd, rsv) = ByteArray(3).apply { input.read(this) }
        if (ver != VER) {
            throw Error("Unsupported protocol")
        }
        if (cmd != CMD_CONNECT) {
            output.write(byteArrayOf(VER, REP_COMMAND_NOT_SUPPORTED, 0x00, 1, 0, 0, 0, 0, 0, 0))
            throw Error("Unsupported protocol")
        }
        val address = runCatching { Address.fromStream(input) }.getOrElse { e ->
            output.write(byteArrayOf(VER, REP_ADDRESS_TYPE_NOT_SUPPORTED, 0x00, 1, 0, 0, 0, 0, 0, 0))
            throw e
        }
        val port = ByteBuffer.wrap(ByteArray(2).apply { input.read(this) })
            .short.toInt().and(0xffff)
        return address to port
    }

    private fun handleStreamOneSide(from: ClientSocket, to: ClientSocket, aliveChannel: Channel<Unit>) {
        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = from.inputStream.read(buffer)
            while (bytes >= 0) {
                to.outputStream.write(buffer, 0, bytes)
                bytes = from.inputStream.read(buffer)
                aliveChannel.trySend(Unit)
            }
        } catch (e: Exception) {
            when (e) {
                is java.net.SocketException if e.message == "Socket closed" -> {}
                else -> logger("Error:" + e.message.toString())
            }
        }
        try {
            if (!to.isClosed) {
                to.shutdownOutput()
            }
        } catch (e: Exception) {
            when (e) {
                is java.net.SocketException if e.message == "Socket is closed" -> {}
                is java.io.IOException if e.message == "socket not created" -> {}
                else -> logger("Error:" + e.message.toString())
            }
        }
        return
    }
}