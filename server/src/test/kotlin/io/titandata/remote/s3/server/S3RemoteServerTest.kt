/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.s3.server

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.internal.AmazonS3ExceptionBuilder
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.model.S3ObjectInputStream
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
import io.titandata.remote.RemoteOperation
import io.titandata.remote.RemoteOperationType
import io.titandata.remote.RemoteProgress
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.IllegalArgumentException

class S3RemoteServerTest : StringSpec() {

    @SpyK
    var server = S3RemoteServer()

    val operation = RemoteOperation(
            updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
            remote = mapOf("bucket" to "bucket", "path" to "path"),
            parameters = emptyMap(),
            operationId = "operation",
            commitId = "commit",
            commit = null,
            type = RemoteOperationType.PUSH
    )

    override fun beforeTest(testCase: TestCase) {
        return MockKAnnotations.init(this)
    }

    override fun afterTest(testCase: TestCase, result: TestResult) {
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
            val result = server.validateRemote(mapOf("bucket" to "bucket", "secretKey" to "secret",
                    "accessKey" to "access", "path" to "/path", "region" to "region"))
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
            val result = server.validateParameters(mapOf("accessKey" to "access", "secretKey" to "secret",
                    "region" to "region", "sessionToken" to "token"))
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

        "get client uses basic session credentials" {
            mockkStatic(AmazonS3ClientBuilder::class)
            val builder = mockk<AmazonS3ClientBuilder>()
            every { AmazonS3ClientBuilder.standard() } returns builder
            every { builder.withRegion(any<String>()) } returns builder
            val slot = slot<AWSCredentialsProvider>()
            every { builder.withCredentials(capture(slot)) } returns builder
            every { builder.build() } returns mockk()

            server.getClient(mapOf("accessKey" to "accessKey", "secretKey" to "secretKey", "region" to "region"), emptyMap())

            val creds = slot.captured
            creds.credentials.awsAccessKeyId shouldBe "accessKey"
            creds.credentials.awsSecretKey shouldBe "secretKey"

            verify {
                builder.withRegion("region")
            }
        }

        "get client uses session token" {
            mockkStatic(AmazonS3ClientBuilder::class)
            val builder = mockk<AmazonS3ClientBuilder>()
            every { AmazonS3ClientBuilder.standard() } returns builder
            every { builder.withRegion(any<String>()) } returns builder
            val slot = slot<AWSCredentialsProvider>()
            every { builder.withCredentials(capture(slot)) } returns builder
            every { builder.build() } returns mockk()

            server.getClient(mapOf("accessKey" to "accessKey", "secretKey" to "secretKey", "region" to "region"), mapOf("sessionToken" to "token"))

            val creds = slot.captured
            creds.credentials.awsAccessKeyId shouldBe "accessKey"
            creds.credentials.awsSecretKey shouldBe "secretKey"
            (creds.credentials as BasicSessionCredentials).sessionToken shouldBe "token"

            verify {
                builder.withRegion("region")
            }
        }

        "get commit fails if no user metadata present" {
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } returns ObjectMetadata()
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
            verify {
                s3.getObjectMetadata("bucket", "path/id")
            }
        }

        "get commit fails if metadata property is missing" {
            val metadata = ObjectMetadata()
            metadata.userMetadata = mapOf()
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } returns metadata
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit fails if metadata is missing properties" {
            val metadata = ObjectMetadata()
            metadata.userMetadata = mapOf("io.titan-data" to "{}")
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } returns metadata
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit succeeds" {
            val metadata = ObjectMetadata()
            metadata.userMetadata = mapOf("io.titan-data" to "{\"properties\":{\"a\":\"b\"}}")
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } returns metadata
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "b"
        }

        "get commit returns null on 404 exception" {
            val s3: AmazonS3Client = mockk()
            val exceptionBuilder = AmazonS3ExceptionBuilder()
            exceptionBuilder.statusCode = 404
            every { s3.getObjectMetadata(any(), any()) } throws exceptionBuilder.build()
            every { server.getClient(any(), any()) } returns s3
            val result = server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit fails on other exceptions" {
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } throws AmazonS3ExceptionBuilder().build()
            every { server.getClient(any(), any()) } returns s3
            shouldThrow<AmazonS3Exception> {
                server.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            }
        }

        "get metadata key returns titan if path is null" {
            server.getMetadataKey(null) shouldBe "titan"
        }

        "get metadata key returns path directory if set" {
            server.getMetadataKey("path") shouldBe "path/titan"
        }

        "get metadata content succeeds" {
            val obj: S3Object = mockk()
            every { obj.objectContent } returns S3ObjectInputStream(ByteArrayInputStream("test".toByteArray()), null)
            val s3: AmazonS3Client = mockk()
            every { s3.getObject(any<String>(), any<String>()) } returns obj
            every { server.getClient(any(), any()) } returns s3
            val result = server.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            result.bufferedReader().readText() shouldBe "test"
            verify {
                s3.getObject("bucket", "path/titan")
            }
        }

        "get metadata content returns empty string on 404 error" {
            val exceptionBuilder = AmazonS3ExceptionBuilder()
            exceptionBuilder.statusCode = 404
            val s3: AmazonS3Client = mockk()
            every { s3.getObject(any<String>(), any<String>()) } throws exceptionBuilder.build()
            every { server.getClient(any(), any()) } returns s3
            val result = server.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            result.bufferedReader().readText() shouldBe ""
        }

        "get metadata content fails on unknown exception" {
            val s3: AmazonS3Client = mockk()
            every { s3.getObject(any<String>(), any<String>()) } throws AmazonS3ExceptionBuilder().build()
            every { server.getClient(any(), any()) } returns s3
            shouldThrow<AmazonS3Exception> {
                server.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            }
        }

        "list commits returns an empty list" {
            every { server.getMetadataContent(any(), any()) } returns ByteArrayInputStream("".toByteArray())
            val result = server.listCommits(emptyMap(), emptyMap(), emptyList())
            result.size shouldBe 0
        }

        "list commits filters result" {
            every { server.getMetadataContent(any(), any()) } returns ByteArrayInputStream(
                    arrayOf("{\"id\":\"a\",\"properties\":{\"tags\":{\"c\":\"d\"}}}",
                            "{\"id\":\"b\",\"properties\":{}}")
                            .joinToString("\n").toByteArray())
            val result = server.listCommits(emptyMap(), emptyMap(), listOf("c" to null))
            result.size shouldBe 1
            result[0].first shouldBe "a"
        }

        "append metadata succeeds" {
            val obj: S3Object = mockk()
            val currentContent = "{\"id\":\"a\",\"properties\":{}}\n"
            every { obj.objectContent } returns S3ObjectInputStream(ByteArrayInputStream(currentContent.toByteArray()), null)
            val metadata = ObjectMetadata()
            metadata.contentLength = currentContent.length.toLong()
            every { obj.objectMetadata } returns metadata
            val s3: AmazonS3Client = mockk()
            every { s3.getObject(any<String>(), any<String>()) } returns obj
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<PutObjectRequest>()
            every { s3.putObject(capture(slot)) } returns mockk()

            server.appendMetadata(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(),
                    "{\"id\":\"b\",\"properties\":{})")
            slot.captured.bucketName shouldBe "bucket"
            slot.captured.key shouldBe "path/titan"
            val newContent = slot.captured.inputStream.bufferedReader().use(BufferedReader::readText)
            newContent shouldBe "{\"id\":\"a\",\"properties\":{}}\n{\"id\":\"b\",\"properties\":{})\n"
        }

        "append metadata treats 404 as empty" {
            val s3: AmazonS3Client = mockk()
            val exceptionBuilder = AmazonS3ExceptionBuilder()
            exceptionBuilder.statusCode = 404
            every { s3.getObject(any<String>(), any<String>()) } throws exceptionBuilder.build()
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<PutObjectRequest>()
            every { s3.putObject(capture(slot)) } returns mockk()

            server.appendMetadata(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(),
                    "{\"id\":\"b\",\"properties\":{}}")
            slot.captured.bucketName shouldBe "bucket"
            slot.captured.key shouldBe "path/titan"
            val newContent = slot.captured.inputStream.bufferedReader().use(BufferedReader::readText)
            newContent shouldBe "{\"id\":\"b\",\"properties\":{}}\n"
        }

        "append metadata passes other exceptions through" {
            val s3: AmazonS3Client = mockk()
            val exceptionBuilder = AmazonS3ExceptionBuilder()
            exceptionBuilder.statusCode = 403
            every { s3.getObject(any<String>(), any<String>()) } throws exceptionBuilder.build()
            every { server.getClient(any(), any()) } returns s3

            shouldThrow<AmazonS3Exception> {
                server.appendMetadata(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(),
                        "{\"id\":\"b\",\"properties\":{}}")
            }
        }

        "update metadata replaces content" {
            val s3: AmazonS3Client = mockk()
            every { server.getClient(any(), any()) } returns s3
            every { server.listCommits(any(), any(), any()) } returns listOf(
                "a" to emptyMap(), "b" to emptyMap())
            every { s3.putObject(any<String>(), any<String>(), any<String>()) } returns mockk()

            server.updateMetadata(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(),
                    "a", mapOf("x" to "y"))

            verify {
                s3.putObject("bucket", "path/titan",
                        "{\"id\":\"a\",\"properties\":{\"x\":\"y\"}}\n{\"id\":\"b\",\"properties\":{}}\n")
            }
        }

        "sync data start returns data object" {
            every { server.getClient(any(), any()) } returns mockk()
            val data = server.syncDataStart(operation) as S3RemoteServer.S3Operation
            data.bucket shouldBe "bucket"
            data.key shouldBe "path/commit"
        }

        "sync data end does nothing" {
            server.syncDataEnd(operation, null, true)
        }

        "pull archive writes contents to file" {
            val s3: AmazonS3Client = mockk()
            every { server.getClient(any(), any()) } returns s3
            val data = S3RemoteServer.S3Operation(
                    provider = server,
                    operation = operation)
            val obj: S3Object = mockk()
            every { s3.getObject(any<String>(), any<String>()) } returns obj
            every { obj.objectContent } returns S3ObjectInputStream(ByteArrayInputStream("test".toByteArray()), null)

            val file = createTempFile()
            server.pullArchive(operation, data, "volume", file)

            val contents = file.readText()
            contents shouldBe "test"

            verify {
                s3.getObject("bucket", "path/commit/volume.tar.gz")
            }
        }

        "push archive succeeds" {
            val s3: AmazonS3Client = mockk()
            every { server.getClient(any(), any()) } returns s3
            val data = S3RemoteServer.S3Operation(
                    provider = server,
                    operation = operation)
            every { s3.putObject(any<String>(), any<String>(), any<File>()) } returns mockk()

            val file = createTempFile()
            server.pushArchive(operation, data, "volume", file)

            verify {
                s3.putObject("bucket", "path/commit/volume.tar.gz", any<File>())
            }
        }

        "push metadata with update calls update metadata" {
            val s3: AmazonS3Client = mockk()
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<PutObjectRequest>()
            every { s3.putObject(capture(slot)) } returns mockk()
            every { server.updateMetadata(any(), any(), any(), any()) } just Runs

            server.pushMetadata(operation, mapOf("a" to "b"), true)

            slot.captured.bucketName shouldBe "bucket"
            slot.captured.key shouldBe "path/commit"
            slot.captured.metadata.userMetadata["io.titan-data"] shouldBe "{\"id\":\"commit\",\"properties\":{\"a\":\"b\"}}"

            verify {
                server.updateMetadata(any(), any(), "commit", mapOf("a" to "b"))
            }
        }

        "push metadata without update calls append metadata" {
            val s3: AmazonS3Client = mockk()
            every { server.getClient(any(), any()) } returns s3
            val slot = slot<PutObjectRequest>()
            every { s3.putObject(capture(slot)) } returns mockk()
            every { server.appendMetadata(any(), any(), any()) } just Runs

            server.pushMetadata(operation, mapOf("a" to "b"), false)

            slot.captured.bucketName shouldBe "bucket"
            slot.captured.key shouldBe "path/commit"
            slot.captured.metadata.userMetadata["io.titan-data"] shouldBe "{\"id\":\"commit\",\"properties\":{\"a\":\"b\"}}"

            verify {
                server.appendMetadata(any(), any(), "{\"id\":\"commit\",\"properties\":{\"a\":\"b\"}}")
            }
        }
    }
}
