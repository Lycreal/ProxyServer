package dev.proxyserver

import android.net.LocalSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress


interface ClientSocket : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val remoteAddress: Any
    val isClosed: Boolean

    @Throws(java.io.IOException::class)
    fun shutdownOutput()
}


object ClientSocketFactory {
    fun wrap(socket: Socket): ClientSocket = SocketWrapper(socket)
    fun wrap(localSocket: LocalSocket): ClientSocket = LocalSocketWrapper(localSocket)
}


private class SocketWrapper(val wrapped: Socket) : ClientSocket {
    override val inputStream: InputStream
        get() = wrapped.inputStream
    override val outputStream: OutputStream
        get() = wrapped.outputStream
    override val remoteAddress: SocketAddress
        get() = wrapped.remoteSocketAddress
    override val isClosed: Boolean
        get() = wrapped.isClosed

    override fun close() = wrapped.close()
    override fun shutdownOutput() = wrapped.shutdownOutput()
}

private class LocalSocketWrapper(val wrapped: LocalSocket) : ClientSocket {
    override val inputStream: InputStream
        get() = wrapped.inputStream
    override val outputStream: OutputStream
        get() = wrapped.outputStream
    override val remoteAddress: Any
        get() = wrapped.fileDescriptor
    override val isClosed: Boolean
        get() = wrapped.isClosed

    override fun close() = wrapped.close()
    override fun shutdownOutput() = wrapped.shutdownOutput()
}