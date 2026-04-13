package com.example.sensorcollector.lifelogging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class UploadClientTest {

    @Test
    fun `upload sends metadata and image multipart parts`() {
        val requestBodyRef = AtomicReference<ByteArray>()
        val contentTypeRef = AtomicReference<String>()
        val serverSocket = ServerSocket(0)
        val serverThread = Thread {
            serverSocket.accept().use { socket ->
                val requestBytes = socket.getInputStream().readUntilHeaderEnd()
                val headerText = String(requestBytes, StandardCharsets.ISO_8859_1)
                val contentLength = extractContentLength(headerText)
                val bodyBytes = ByteArray(contentLength)
                socket.getInputStream().readFully(bodyBytes)
                contentTypeRef.set(extractContentType(headerText))
                requestBodyRef.set(bodyBytes)
                val responseBody = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
                val responseHeader = (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${responseBody.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.ISO_8859_1)
                socket.getOutputStream().write(responseHeader)
                socket.getOutputStream().write(responseBody)
                socket.getOutputStream().flush()
            }
        }
        serverThread.start()

        val tempImage = File.createTempFile("upload-client-test-", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            deleteOnExit()
        }

        try {
            val port = serverSocket.localPort
            val client = UploadClient("http://127.0.0.1:$port/upload")
            val snapshot = LifelogSnapshot(
                id = UUID.randomUUID().toString(),
                timestampMs = 1_713_000_000_000,
                placeName = "Test Place",
                latitude = 30.0,
                longitude = 120.0,
                motion = MotionSnapshot(
                    speedMps = 1.5f,
                    motionEnergy = 0.8,
                    state = ActivityState.WALKING
                ),
                samplingDecision = SamplingDecision(
                    mode = SamplingMode.HIGH_FREQUENCY,
                    intervalMs = 5_000L,
                    reasons = listOf("state_changed")
                ),
                eventEstimate = LocalEventEstimate(
                    eventType = CoarseEventType.STUDYING,
                    confidence = 0.75,
                    worthUploading = true,
                    possibleNewEvent = true,
                    reasons = listOf("fresh_photo", "state_changed")
                ),
                imageFileName = tempImage.name
            )

            val uploaded = client.upload(snapshot, tempImage)

            assertTrue(uploaded)
            val contentType = contentTypeRef.get().orEmpty()
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))

            val body = requestBodyRef.get()
            val bodyText = String(body, StandardCharsets.ISO_8859_1)
            assertTrue(bodyText.contains("Content-Disposition: form-data; name=\"metadata\""))
            assertTrue(bodyText.contains("\"id\":\"${snapshot.id}\""))
            assertTrue(bodyText.contains("\"eventType\":\"${snapshot.eventEstimate.eventType.name}\""))
            assertTrue(
                bodyText.contains(
                    "Content-Disposition: form-data; name=\"image\"; filename=\"${tempImage.name}\""
                )
            )
            val imageHeaderIndex = bodyText.indexOf("Content-Type: image/jpeg\r\n\r\n")
            assertTrue(imageHeaderIndex >= 0)
            val imageStart = imageHeaderIndex + "Content-Type: image/jpeg\r\n\r\n".length
            val imageEnd = body.indexOfSubsequence(
                "\r\n--".toByteArray(StandardCharsets.ISO_8859_1),
                imageStart
            )
            val uploadedImageBytes = body.copyOfRange(imageStart, imageEnd)
            assertArrayEquals(tempImage.readBytes(), uploadedImageBytes)
        } finally {
            serverSocket.close()
            serverThread.join(2000)
            tempImage.delete()
        }
    }

    private fun java.io.InputStream.readUntilHeaderEnd(): ByteArray {
        val buffer = ArrayList<Byte>()
        var matchIndex = 0
        val delimiter = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        while (true) {
            val value = read()
            if (value == -1) {
                break
            }
            buffer.add(value.toByte())
            if (value.toByte() == delimiter[matchIndex]) {
                matchIndex += 1
                if (matchIndex == delimiter.size) {
                    break
                }
            } else {
                matchIndex = if (value.toByte() == delimiter[0]) 1 else 0
            }
        }
        return buffer.toByteArray()
    }

    private fun java.io.InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            if (count == -1) {
                error("Unexpected end of stream")
            }
            offset += count
        }
    }

    private fun extractContentLength(headers: String): Int {
        val line = headers.lineSequence()
            .first { it.startsWith("Content-Length:", ignoreCase = true) }
        return line.substringAfter(":").trim().toInt()
    }

    private fun extractContentType(headers: String): String {
        val line = headers.lineSequence()
            .first { it.startsWith("Content-Type:", ignoreCase = true) }
        return line.substringAfter(":").trim()
    }

    private fun ByteArray.indexOfSubsequence(target: ByteArray, startIndex: Int): Int {
        for (index in startIndex..(size - target.size)) {
            var matched = true
            for (offset in target.indices) {
                if (this[index + offset] != target[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return index
            }
        }
        return size
    }
}
