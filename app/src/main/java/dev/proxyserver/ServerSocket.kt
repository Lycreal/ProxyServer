package dev.proxyserver

import android.net.LocalServerSocket
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket


interface MyServerSocket : Closeable {
    @Throws(IOException::class)
    fun accept(): ClientSocket
}


object MyServerSocketFactory {
    fun wrap(socket: ServerSocket): MyServerSocket = ServerSocketWrapper(socket)
    fun wrap(localSocket: LocalServerSocket): MyServerSocket = LocalServerSocketWrapper(localSocket)
}


private class ServerSocketWrapper(val wrapped: ServerSocket) : MyServerSocket {
    override fun accept() = ClientSocketFactory.wrap(wrapped.accept())
    override fun close() = wrapped.close()
}

private class LocalServerSocketWrapper(val wrapped: LocalServerSocket) : MyServerSocket {
    override fun accept() = ClientSocketFactory.wrap(wrapped.accept())
    override fun close() {
        Os.shutdown(wrapped.fileDescriptor, OsConstants.SHUT_RDWR)
        wrapped.close()
    }
}