// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.remote.s3.server

import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteProgress
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.SpyK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.IllegalArgumentException
import kotlin.io.path.createTempFile

/**
 * An [InputStream] wrapper that records every call to [close] so tests can verify
 * resource-cleanup invariants. Using a real tracking stream rather than relying on
 * [io.mockk.mockk]'s auto-stubbing of `close()` (which silently masks leaks).
 */
private class TrackingInputStream(
    delegate: InputStream,
) : java.io.FilterInputStream(delegate) {
    val closeCount = AtomicInteger(0)

    override fun close() {
        closeCount.incrementAndGet()
        super.close()
    }
}

class S3RemoteServerTest : StringSpec() {
    @SpyK
    var server = S3RemoteServer()

    val operation =
        RemoteOperation(
            updateProgress = { _: RemoteProgress, _: String?, _: Int? -> },
            remote = mapOf("bucket" to "bucket", "path" to "path"),
            parameters = emptyMap(),
            operationId = "operation",
            commitId = "commit",
            commit = null,
            type = RemoteOperationType.PUSH,
        )

    override fun beforeTest(testCase: TestCase) = MockKAnnotations.init(this)

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        clearAllMocks()
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        "get provider returns s3" {
            server.getProvider() shouldBe "s3"
        }

        "validate remote succeeds with only required properties" {
            val result = server.validateRemote(mapOf("bucket" to "bucket"))
            result["bucket"] shouldBe "bucket"
        }

        "validate remote succeeds with all properties" {
            val result =
                server.validateRemote(
                    mapOf(
                        "bucket" to "bucket",
                        "secretKey" to "secret",
                        "accessKey" to "access",
                        "path" to "/path",
                        "region" to "region",
                    ),
                )
            result["bucket"] shouldBe "bucket"
            result["secretKey"] shouldBe "secret"
            result["accessKey"] shouldBe "access"
            result["path"] shouldBe "/path"
            result["region"] shouldBe "region"
        }

        "validate remote fails with missing required property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(emptyMap())
            }
        }

        "validate remote fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("bucket" to "bucket", "bucketz" to "bucket"))
            }
        }

        "validate remote fails if only access key is set" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("bucket" to "bucket", "accessKey" to "access"))
            }
        }

        "validate remote fails if only secret key is set" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("bucket" to "bucket", "secretKey" to "secret"))
            }
        }

        "validate parameters succeeds with empty properties" {
            val result = server.validateParameters(emptyMap())
            result.size shouldBe 0
        }

        "validate parameters succeeds with all properties" {
            val result =
                server.validateParameters(
                    mapOf(
                        "accessKey" to "access",
                        "secretKey" to "secret",
                        "region" to "region",
                        "sessionToken" to "token",
                    ),
                )
            result["accessKey"] shouldBe "access"
            result["secretKey"] shouldBe "secret"
            result["region"] shouldBe "region"
            result["sessionToken"] shouldBe "token"
        }

        "validate parameters fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("foo" to "bar"))
            }
        }

        "get path returns bucket" {
            val (bucket) = server.getPath(mapOf("bucket" to "bucket"))
            bucket shouldBe "bucket"
        }

        "get path returns commit ID if path not specified" {
            val result = server.getPath(mapOf("bucket" to "bucket"), "id")
            result.second shouldBe "id"
        }

        "get path returns path if commit ID not set" {
            val result = server.getPath(mapOf("bucket" to "bucket", "path" to "key"))
            result.second shouldBe "key"
        }

        "get path returns path plus commit ID if set" {
            val result = server.getPath(mapOf("bucket" to "bucket", "path" to "key"), "id")
            result.second shouldBe "key/id"
        }

        "get client fails with no access key" {
            shouldThrow<IllegalArgumentException> {
                server.getClient(mapOf("secretKey" to "secretKey", "region" to "region"), emptyMap())
            }
        }

        "get client fails with no secret key" {
            shouldThrow<IllegalArgumentException> {
                server.getClient(mapOf("accessKey" to "accessKey", "region" to "region"), emptyMap())
            }
        }

        "get client fails with no region" {
            shouldThrow<IllegalArgumentException> {
                server.getClient(mapOf("accessKey" to "accessKey", "secretKey" to "secretKey"), emptyMap())
            }
        }

        "get client uses basic credentials" {
            val client =
                server.getClient(
                    mapOf("accessKey" to "accessKey", "secretKey" to "secretKey", "region" to "us-east-1"),
                    emptyMap(),
                )
            client shouldNotBe null
        }

        "get client uses session token" {
            val client =
                server.getClient(
                    mapOf("accessKey" to "accessKey", "secretKey" to "secretKey", "region" to "us-east-1"),
                    mapOf("sessionToken" to "token"),
                )
            client shouldNotBe null
        }

        "get commit fails if no user metadata present" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns emptyMap()
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit fails if metadata property is missing" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns emptyMap()
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit fails if metadata is missing properties" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns mapOf("dev.dit" to "{}")
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit succeeds" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns mapOf("dev.dit" to "{\"properties\":{\"a\":\"b\"}}")
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "b"
        }

        "get commit succeeds with legacy com.dit key" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns mapOf("com.dit" to "{\"properties\":{\"a\":\"b\"}}")
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "b"
        }

        "get commit succeeds with legacy com.datadatdat key" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns mapOf("com.datadatdat" to "{\"properties\":{\"a\":\"b\"}}")
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "b"
        }

        "get commit prefers canonical dev.dit over legacy keys" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns
                mapOf(
                    "dev.dit" to "{\"properties\":{\"a\":\"canonical\"}}",
                    "com.dit" to "{\"properties\":{\"a\":\"legacy-dit\"}}",
                    "com.datadatdat" to "{\"properties\":{\"a\":\"legacy-ddd\"}}",
                )
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "canonical"
        }

        "get commit skips empty metadata values and falls through to null" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns mapOf("dev.dit" to "")
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit returns null on NoSuchKey exception" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } throws NoSuchKeyException.builder().build()
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit returns null on 404 exception" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            val exception = S3Exception.builder().statusCode(404).build()
            every { s3.headObject(any<HeadObjectRequest>()) } throws exception
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit fails on other exceptions" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder().statusCode(500).build()
            every { server.getClient(any(), any()) } returns s3
            shouldThrow<S3Exception> {
                server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            }
        }

        "get metadata key returns dit if path is null" {
            server.getMetadataKey(null) shouldBe "dit"
        }

        "get metadata key returns path/dit if path is not null" {
            server.getMetadataKey("path") shouldBe "path/dit"
        }

        "get metadata content succeeds" {
            val response: GetObjectResponse = mockk()
            val responseStream = ResponseInputStream(response, ByteArrayInputStream("test".toByteArray()))
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } returns responseStream
            every { server.getClient(any(), any()) } returns s3
            val result = server.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            result.bufferedReader().readText() shouldBe "test"
        }

        "get metadata content returns empty string on NoSuchKey error" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } throws NoSuchKeyException.builder().build()
            every { server.getClient(any(), any()) } returns s3
            val result = server.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            result.bufferedReader().readText() shouldBe ""
        }

        "get metadata content returns empty string on 404 error" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } throws S3Exception.builder().statusCode(404).build()
            every { server.getClient(any(), any()) } returns s3
            val result = server.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            result.bufferedReader().readText() shouldBe ""
        }

        "get metadata content fails on unknown exception" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } throws S3Exception.builder().statusCode(500).build()
            every { server.getClient(any(), any()) } returns s3
            shouldThrow<S3Exception> {
                server.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            }
        }

        "list commits returns an empty list" {
            every { server.getMetadataContent(any(), any()) } returns ByteArrayInputStream("".toByteArray())
            val result = server.listCommits(emptyMap(), emptyMap(), emptyList())
            result.size shouldBe 0
        }

        "list commits filters result" {
            every { server.getMetadataContent(any(), any()) } returns
                ByteArrayInputStream(
                    arrayOf(
                        "{\"id\":\"a\",\"properties\":{\"tags\":{\"c\":\"d\"}}}",
                        "{\"id\":\"b\",\"properties\":{}}",
                    ).joinToString("\n")
                        .toByteArray(),
                )
            val result = server.listCommits(emptyMap(), emptyMap(), listOf("c" to null))
            result.size shouldBe 1
            result[0].first shouldBe "a"
        }

        "append metadata succeeds" {
            val currentContent = "{\"id\":\"a\",\"properties\":{}}\n"
            val response: GetObjectResponse = mockk(relaxed = true)
            every { response.contentLength() } returns currentContent.length.toLong()
            val inputStream = ByteArrayInputStream(currentContent.toByteArray())
            val responseStream = ResponseInputStream(response, inputStream)
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } returns responseStream
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<RequestBody>()
            every { s3.putObject(any<PutObjectRequest>(), capture(slot)) } returns mockk()

            server.appendMetadata(
                mapOf("bucket" to "bucket", "path" to "path"),
                emptyMap(),
                "{\"id\":\"b\",\"properties\":{})",
            )
            val newContent =
                slot.captured
                    .contentStreamProvider()
                    .newStream()
                    .bufferedReader()
                    .use(BufferedReader::readText)
            newContent shouldBe "{\"id\":\"a\",\"properties\":{}}\n{\"id\":\"b\",\"properties\":{})\n"
        }

        "append metadata treats NoSuchKey as empty" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } throws NoSuchKeyException.builder().build()
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<RequestBody>()
            every { s3.putObject(any<PutObjectRequest>(), capture(slot)) } returns mockk()

            server.appendMetadata(
                mapOf("bucket" to "bucket", "path" to "path"),
                emptyMap(),
                "{\"id\":\"b\",\"properties\":{}}",
            )
            val newContent =
                slot.captured
                    .contentStreamProvider()
                    .newStream()
                    .bufferedReader()
                    .use(BufferedReader::readText)
            newContent shouldBe "{\"id\":\"b\",\"properties\":{}}\n"
        }

        "append metadata treats 404 as empty" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } throws S3Exception.builder().statusCode(404).build()
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<RequestBody>()
            every { s3.putObject(any<PutObjectRequest>(), capture(slot)) } returns mockk()

            server.appendMetadata(
                mapOf("bucket" to "bucket", "path" to "path"),
                emptyMap(),
                "{\"id\":\"b\",\"properties\":{}}",
            )
            val newContent =
                slot.captured
                    .contentStreamProvider()
                    .newStream()
                    .bufferedReader()
                    .use(BufferedReader::readText)
            newContent shouldBe "{\"id\":\"b\",\"properties\":{}}\n"
        }

        "append metadata passes other exceptions through" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } throws S3Exception.builder().statusCode(403).build()
            every { server.getClient(any(), any()) } returns s3

            shouldThrow<S3Exception> {
                server.appendMetadata(
                    mapOf("bucket" to "bucket", "path" to "path"),
                    emptyMap(),
                    "{\"id\":\"b\",\"properties\":{}}",
                )
            }
        }

        "update metadata replaces content" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { server.getClient(any(), any()) } returns s3
            every { server.listCommits(any(), any(), any()) } returns
                listOf(
                    "a" to emptyMap(),
                    "b" to emptyMap(),
                )
            val slot = slot<RequestBody>()
            every { s3.putObject(any<PutObjectRequest>(), capture(slot)) } returns mockk()

            server.updateMetadata(
                mapOf("bucket" to "bucket", "path" to "path"),
                emptyMap(),
                "a",
                mapOf("x" to "y"),
            )

            val content =
                slot.captured
                    .contentStreamProvider()
                    .newStream()
                    .bufferedReader()
                    .use(BufferedReader::readText)
            content shouldBe "{\"id\":\"a\",\"properties\":{\"x\":\"y\"}}\n{\"id\":\"b\",\"properties\":{}}\n"
        }

        "sync data start returns data object" {
            every { server.getClient(any(), any()) } returns mockk(relaxUnitFun = true)
            val data = server.syncDataStart(operation) as S3RemoteServer.S3Operation
            data.bucket shouldBe "bucket"
            data.key shouldBe "path/commit"
        }

        "sync data end closes s3 client" {
            every { server.getClient(any(), any()) } returns mockk(relaxUnitFun = true)
            val data = S3RemoteServer.S3Operation(provider = server, operation = operation)
            server.syncDataEnd(operation, data, true)
        }

        "sync data end handles null data" {
            server.syncDataEnd(operation, null, true)
        }

        "pull archive writes contents to file" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { server.getClient(any(), any()) } returns s3
            val data =
                S3RemoteServer.S3Operation(
                    provider = server,
                    operation = operation,
                )
            val response: GetObjectResponse = mockk()
            val responseStream = ResponseInputStream(response, ByteArrayInputStream("test".toByteArray()))
            every { s3.getObject(any<GetObjectRequest>()) } returns responseStream

            val file = createTempFile().toFile()
            server.pullArchive(operation, data, "volume", file)

            val contents = file.readText()
            contents shouldBe "test"
        }

        "push archive succeeds" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { server.getClient(any(), any()) } returns s3
            val data =
                S3RemoteServer.S3Operation(
                    provider = server,
                    operation = operation,
                )
            every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns mockk()

            val file = createTempFile().toFile()
            server.pushArchive(operation, data, "volume", file)

            verify {
                s3.putObject(any<PutObjectRequest>(), any<RequestBody>())
            }
        }

        "push metadata with update calls update metadata" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { server.getClient(any(), any()) } returns s3
            val requestSlot = slot<PutObjectRequest>()
            every { s3.putObject(capture(requestSlot), any<RequestBody>()) } returns mockk()
            every { server.updateMetadata(any(), any(), any(), any()) } just Runs

            server.pushMetadata(operation, mapOf("a" to "b"), true)

            requestSlot.captured.bucket() shouldBe "bucket"
            requestSlot.captured.key() shouldBe "path/commit"
            requestSlot.captured.metadata()["dev.dit"] shouldBe "{\"id\":\"commit\",\"properties\":{\"a\":\"b\"}}"

            verify {
                server.updateMetadata(any(), any(), "commit", mapOf("a" to "b"))
            }
        }

        "push metadata without update calls append metadata" {
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { server.getClient(any(), any()) } returns s3
            val requestSlot = slot<PutObjectRequest>()
            every { s3.putObject(capture(requestSlot), any<RequestBody>()) } returns mockk()
            every { server.appendMetadata(any(), any(), any()) } just Runs

            server.pushMetadata(operation, mapOf("a" to "b"), false)

            requestSlot.captured.bucket() shouldBe "bucket"
            requestSlot.captured.key() shouldBe "path/commit"
            requestSlot.captured.metadata()["dev.dit"] shouldBe "{\"id\":\"commit\",\"properties\":{\"a\":\"b\"}}"

            verify {
                server.appendMetadata(any(), any(), "{\"id\":\"commit\",\"properties\":{\"a\":\"b\"}}")
            }
        }

        // Coverage tests for uncovered branches

        "gson companion property is accessible" {
            S3RemoteServer.gson shouldNotBe null
        }

        "util companion property is accessible" {
            S3RemoteServer.util shouldNotBe null
        }

        "validate parameters with null input returns empty map" {
            val result = server.validateParameters(null)
            result shouldBe emptyMap()
        }

        "get client with credentials from parameters" {
            val client =
                server.getClient(
                    mapOf("bucket" to "bucket"),
                    mapOf("accessKey" to "key", "secretKey" to "secret", "region" to "us-east-1"),
                )
            client shouldNotBe null
        }

        "get commit with null metadata returns null" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns null
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3

            val result = server.getCommit(mapOf("bucket" to "bucket"), emptyMap(), "commit")
            result shouldBe null
        }

        "get commit with metadata missing properties key" {
            val response: HeadObjectResponse = mockk()
            every { response.metadata() } returns mapOf("dev.dit" to "{\"someOtherKey\":\"value\"}")
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.headObject(any<HeadObjectRequest>()) } returns response
            every { server.getClient(any(), any()) } returns s3

            val result = server.getCommit(mapOf("bucket" to "bucket"), emptyMap(), "commit")
            result shouldBe null
        }

        "list commits handles malformed JSON with missing id" {
            val content = "{\"properties\":{}}\n"
            val metadata = ByteArrayInputStream(content.toByteArray())
            every { server.getMetadataContent(any(), any()) } returns metadata

            val result = server.listCommits(emptyMap(), emptyMap(), emptyList())
            result.size shouldBe 0
        }

        "list commits handles malformed JSON with missing properties" {
            val content = "{\"id\":\"commit-id\"}\n"
            val metadata = ByteArrayInputStream(content.toByteArray())
            every { server.getMetadataContent(any(), any()) } returns metadata

            val result = server.listCommits(emptyMap(), emptyMap(), emptyList())
            result.size shouldBe 0
        }

        "list commits handles empty lines in metadata" {
            val content = "\n{\"id\":\"a\",\"properties\":{}}\n\n"
            val metadata = ByteArrayInputStream(content.toByteArray())
            every { server.getMetadataContent(any(), any()) } returns metadata

            val result = server.listCommits(emptyMap(), emptyMap(), emptyList())
            result.size shouldBe 1
        }

        "list commits closes metadata stream when parsing throws" {
            // Exercises the exception path: malformed JSON triggers a gson parse
            // exception mid-loop; the stream must still be closed.
            val tracker = TrackingInputStream(ByteArrayInputStream("not-valid-json\n".toByteArray()))
            every { server.getMetadataContent(any(), any()) } returns tracker
            shouldThrow<com.google.gson.JsonSyntaxException> {
                server.listCommits(emptyMap(), emptyMap(), emptyList())
            }
            // BufferedReader.readLines() also closes via reader → stream chain,
            // so >= 1 close is sufficient evidence of cleanup.
            (tracker.closeCount.get() >= 1) shouldBe true
        }

        "list commits propagates exceptions when read throws" {
            // A read failure must surface to the caller, not be swallowed.
            val failingStream =
                object : InputStream() {
                    override fun read(): Int = throw java.io.IOException("read failed")
                }
            every { server.getMetadataContent(any(), any()) } returns failingStream
            shouldThrow<java.io.IOException> {
                server.listCommits(emptyMap(), emptyMap(), emptyList())
            }
        }

        // Resource-cleanup verification (issue #207, finding #1)
        //
        // Verify `appendMetadata` always closes the `ResponseInputStream` it gets
        // back from `s3.getObject`. Uses a real tracking stream rather than relying
        // on `mockk(relaxUnitFun = true)` which silently auto-stubs `close()` and
        // would hide a leak.

        "append metadata closes the response input stream on success" {
            val currentContent = "{\"id\":\"a\",\"properties\":{}}\n"
            val response: GetObjectResponse = mockk(relaxed = true)
            every { response.contentLength() } returns currentContent.length.toLong()
            val tracker = TrackingInputStream(ByteArrayInputStream(currentContent.toByteArray()))
            val responseStream = ResponseInputStream(response, tracker)
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } returns responseStream
            every { server.getClient(any(), any()) } returns s3
            every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns mockk()

            server.appendMetadata(
                mapOf("bucket" to "bucket", "path" to "path"),
                emptyMap(),
                "{\"id\":\"b\",\"properties\":{}}",
            )

            tracker.closeCount.get() shouldBe 1
        }

        "append metadata closes the response stream even when contentLength throws" {
            val response: GetObjectResponse = mockk(relaxed = true)
            // Simulate `response()` returning a response whose `contentLength()`
            // throws — the response stream MUST still be closed (resource-leak
            // shape from issue #207).
            every { response.contentLength() } throws RuntimeException("boom")
            val tracker = TrackingInputStream(ByteArrayInputStream("ignored".toByteArray()))
            val responseStream = ResponseInputStream(response, tracker)
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } returns responseStream
            every { server.getClient(any(), any()) } returns s3

            shouldThrow<RuntimeException> {
                server.appendMetadata(
                    mapOf("bucket" to "bucket", "path" to "path"),
                    emptyMap(),
                    "{\"id\":\"b\",\"properties\":{}}",
                )
            }
            tracker.closeCount.get() shouldBe 1
        }

        // Null-safety verification (issue #207, finding #2)
        //
        // `responseStream.response()` returning null must not NPE; the safe call
        // should fall back to a length of 0.

        "append metadata handles null response without NPE" {
            // Mocks the (final) ResponseInputStream itself so `.response()` returns
            // null, exercising the `?.` safe-call. Without the safe-call this
            // would NPE on `.contentLength()`.
            val responseStream: ResponseInputStream<GetObjectResponse> = mockk(relaxed = true)
            every { responseStream.response() } returns null
            every { responseStream.readAllBytes() } returns ByteArray(0)
            val s3: S3Client = mockk(relaxUnitFun = true)
            every { s3.getObject(any<GetObjectRequest>()) } returns responseStream
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<RequestBody>()
            every { s3.putObject(any<PutObjectRequest>(), capture(slot)) } returns mockk()

            // Must complete without NPE
            server.appendMetadata(
                mapOf("bucket" to "bucket", "path" to "path"),
                emptyMap(),
                "{\"id\":\"b\",\"properties\":{}}",
            )

            // The response stream must still be closed even on the null-response path.
            verify { responseStream.close() }

            val newContent =
                slot.captured
                    .contentStreamProvider()
                    .newStream()
                    .bufferedReader()
                    .use(BufferedReader::readText)
            newContent shouldBe "{\"id\":\"b\",\"properties\":{}}\n"
        }
    }
}
