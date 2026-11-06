package net.imadz.m25.bank.durable

import java.io.{EOFException, IOException}
import java.net.{InetSocketAddress, Socket, SocketTimeoutException}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.collection.mutable

/** Only a loopback wire adapter, not an external service or a workflow engine.
  * One connection per command; callers supply their own bounded I/O executor.
  */
final class LocalTaskClient(val port: Int) extends AutoCloseable {
  require(port > 0 && port <= 65535, "external port must be between 1 and 65535")
  private val timeoutMillis = 2000
  private val maxFrameBytes = 128 * 1024 // Includes the four-byte length prefix.
  private val maxStringBytes = 64 * 1024
  private val sockets = mutable.Set.empty[Socket]
  private var closed = false

  private def encode(fields: Vector[String]): Array[Byte] = {
    if (fields.isEmpty || fields.size > 16) throw new IOException("expected 1..16 strings")
    val encoded = fields.map { field =>
      if (field == null || field.length > maxStringBytes) throw new IOException("oversized string")
      val bytes = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(field))
      if (bytes.remaining() > maxStringBytes) throw new IOException("oversized UTF-8 string")
      bytes
    }
    val bodyLength = 4 + encoded.map(b => 4 + b.remaining()).sum
    if (bodyLength + 4 > maxFrameBytes) throw new IOException("oversized request frame")
    val frame = ByteBuffer.allocate(bodyLength + 4)
    frame.putInt(bodyLength).putInt(fields.size)
    encoded.foreach(b => { frame.putInt(b.remaining()); frame.put(b) })
    frame.array()
  }

  def call(command: String, fields: Vector[String] = Vector.empty): Vector[String] = {
    val request = encode(command +: fields)
    val socket = synchronized {
      if (closed) throw new IOException("external client is stopped")
      val created = new Socket()
      sockets += created
      created
    }
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMillis)
      socket.setSoTimeout(timeoutMillis)
      socket.setTcpNoDelay(true)
      socket.getOutputStream.write(request)
      socket.getOutputStream.flush()
      val in = socket.getInputStream
      // A single deadline for prefix + body also bounds a slow, partial response.
      val deadline = System.nanoTime() + timeoutMillis.toLong * 1000000L
      def readExactly(length: Int): Array[Byte] = {
        val bytes = new Array[Byte](length)
        var offset = 0
        while (offset < length) {
          val remaining = deadline - System.nanoTime()
          if (remaining <= 0) throw new SocketTimeoutException("external frame read exceeded 2 seconds")
          socket.setSoTimeout(math.max(1L, (remaining + 999999L) / 1000000L).toInt)
          val count = in.read(bytes, offset, length - offset)
          if (count < 0) throw new EOFException("external service closed an incomplete frame")
          offset += count
        }
        bytes
      }
      val length = ByteBuffer.wrap(readExactly(4)).getInt()
      if (length < 4 || length > maxFrameBytes - 4) throw new IOException("invalid response frame length")
      val body = ByteBuffer.wrap(readExactly(length))
      val count = body.getInt()
      if (count < 1 || count > 16) throw new IOException("invalid response string count")
      val result = Vector.fill(count) {
        if (body.remaining() < 4) throw new IOException("missing string length")
        val size = body.getInt()
        if (size < 0 || size > maxStringBytes || size > body.remaining())
          throw new IOException("invalid response string length")
        val bytes = new Array[Byte](size)
        body.get(bytes)
        LocalTaskClient.utf8(bytes)
      }
      if (body.hasRemaining) throw new IOException("trailing response bytes")
      result
    } finally {
      try socket.close()
      finally synchronized { sockets -= socket }
    }
  }

  override def close(): Unit = {
    val active = synchronized {
      closed = true
      val all = sockets.toVector
      sockets.clear()
      all
    }
    active.foreach(s => try s.close() catch { case _: IOException => () })
  }
}

object LocalTaskClient {
  private[durable] def utf8(bytes: Array[Byte]): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString
}
