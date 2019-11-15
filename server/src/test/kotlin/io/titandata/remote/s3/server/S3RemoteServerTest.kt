package io.titandata.remote.s3.server

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.internal.AmazonS3ExceptionBuilder
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.ObjectMetadata
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
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.SpyK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import kotlin.IllegalArgumentException

class S3RemoteServerTest : StringSpec() {

    @SpyK
    var client = S3RemoteServer()

    override fun beforeTest(testCase: TestCase) {
        return MockKAnnotations.init(this)
    }

    override fun afterTest(testCase: TestCase, result: TestResult) {
        clearAllMocks()
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        "get provider returns s3" {
            client.getProvider() shouldBe "s3"
        }

        "validate remote succeeds with only required properties" {
            val result = client.validateRemote(mapOf("bucket" to "bucket"))
            result["bucket"] shouldBe "bucket"
        }

        "validate remote succeeds with all properties" {
            val result = client.validateRemote(mapOf("bucket" to "bucket", "secretKey" to "secret",
                    "accessKey" to "access", "path" to "/path", "region" to "region"))
            result["bucket"] shouldBe "bucket"
            result["secretKey"] shouldBe "secret"
            result["accessKey"] shouldBe "access"
            result["path"] shouldBe "/path"
            result["region"] shouldBe "region"
        }

        "validate remote fails with missing required property" {
            shouldThrow<IllegalArgumentException> {
                client.validateRemote(emptyMap())
            }
        }

        "validate remote fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                client.validateRemote(mapOf("bucket" to "bucket", "bucketz" to "bucket"))
            }
        }

        "validate remote fails if only access key is set" {
            shouldThrow<IllegalArgumentException> {
                client.validateRemote(mapOf("bucket" to "bucket", "accessKey" to "access"))
            }
        }

        "validate remote fails if only secret key is set" {
            shouldThrow<IllegalArgumentException> {
                client.validateRemote(mapOf("bucket" to "bucket", "secretKey" to "secret"))
            }
        }

        "validate parameters succeeds with only required properties" {
            val result = client.validateParameters(mapOf("accessKey" to "access", "secretKey" to "secret"))
            result["accessKey"] shouldBe "access"
            result["secretKey"] shouldBe "secret"
        }

        "validate parameters succeeds with all properties" {
            val result = client.validateParameters(mapOf("accessKey" to "access", "secretKey" to "secret",
                    "region" to "region", "sessionToken" to "token"))
            result["accessKey"] shouldBe "access"
            result["secretKey"] shouldBe "secret"
            result["region"] shouldBe "region"
            result["sessionToken"] shouldBe "token"
        }

        "validate parameters fails with missing required property" {
            shouldThrow<IllegalArgumentException> {
                client.validateParameters(emptyMap())
            }
        }

        "validate parameters fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                client.validateRemote(mapOf("accessKey" to "access", "secretKey" to "secret", "foo" to "bar"))
            }
        }

        "get path returns bucket" {
            val (bucket) = client.getPath(mapOf("bucket" to "bucket"))
            bucket shouldBe "bucket"
        }

        "get path returns commit ID if path not specified" {
            val result = client.getPath(mapOf("bucket" to "bucket"), "id")
            result.second shouldBe "id"
        }

        "get path returns path if commit ID not set" {
            val result = client.getPath(mapOf("bucket" to "bucket", "path" to "key"))
            result.second shouldBe "key"
        }

        "get path returns path plus commit ID if set" {
            val result = client.getPath(mapOf("bucket" to "bucket", "path" to "key"), "id")
            result.second shouldBe "key/id"
        }

        "get client fails with no access key" {
            shouldThrow<IllegalArgumentException> {
                client.getClient(mapOf("secretKey" to "secretKey", "region" to "region"), emptyMap())
            }
        }

        "get client fails with no secret key" {
            shouldThrow<IllegalArgumentException> {
                client.getClient(mapOf("accessKey" to "accessKey", "region" to "region"), emptyMap())
            }
        }

        "get client fails with no region" {
            shouldThrow<IllegalArgumentException> {
                client.getClient(mapOf("accessKey" to "accessKey", "secretKey" to "secretKey"), emptyMap())
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

            client.getClient(mapOf("accessKey" to "accessKey", "secretKey" to "secretKey", "region" to "region"), emptyMap())

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

            client.getClient(mapOf("accessKey" to "accessKey", "secretKey" to "secretKey", "region" to "region"), mapOf("sessionToken" to "token"))

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
            every { client.getClient(any(), any()) } returns s3
            val result = client.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
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
            every { client.getClient(any(), any()) } returns s3
            val result = client.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit fails if metadata is missing properties" {
            val metadata = ObjectMetadata()
            metadata.userMetadata = mapOf("io.titan-data" to "{}")
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } returns metadata
            every { client.getClient(any(), any()) } returns s3
            val result = client.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit succeeds" {
            val metadata = ObjectMetadata()
            metadata.userMetadata = mapOf("io.titan-data" to "{\"properties\":{\"a\":\"b\"}}")
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } returns metadata
            every { client.getClient(any(), any()) } returns s3
            val result = client.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "b"
        }

        "get commit returns null on 404 exception" {
            val s3: AmazonS3Client = mockk()
            val exceptionBuilder = AmazonS3ExceptionBuilder()
            exceptionBuilder.statusCode = 404
            every { s3.getObjectMetadata(any(), any()) } throws exceptionBuilder.build()
            every { client.getClient(any(), any()) } returns s3
            val result = client.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit fails on other exceptions" {
            val s3: AmazonS3Client = mockk()
            every { s3.getObjectMetadata(any(), any()) } throws AmazonS3ExceptionBuilder().build()
            every { client.getClient(any(), any()) } returns s3
            shouldThrow<AmazonS3Exception> {
                client.getCommit(mapOf("bucket" to "bucket", "path" to "path"), emptyMap(), "id")
            }
        }

        "get metadata key returns titan if path is null" {
            client.getMetadataKey(null) shouldBe "titan"
        }

        "get metadata key returns path directory if set" {
            client.getMetadataKey("path") shouldBe "path/titan"
        }

        "get metadata content succeeds" {
            val obj: S3Object = mockk()
            every { obj.objectContent } returns S3ObjectInputStream(ByteArrayInputStream("test".toByteArray()), null)
            val s3: AmazonS3Client = mockk()
            every { s3.getObject(any<String>(), any<String>()) } returns obj
            every { client.getClient(any(), any()) } returns s3
            val result = client.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
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
            every { client.getClient(any(), any()) } returns s3
            val result = client.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            result.bufferedReader().readText() shouldBe ""
        }

        "get metadata content fails on unknown exception" {
            val s3: AmazonS3Client = mockk()
            every { s3.getObject(any<String>(), any<String>()) } throws AmazonS3ExceptionBuilder().build()
            every { client.getClient(any(), any()) } returns s3
            shouldThrow<AmazonS3Exception> {
                client.getMetadataContent(mapOf("bucket" to "bucket", "path" to "path"), emptyMap())
            }
        }

        "list commits returns an empty list" {
            every { client.getMetadataContent(any(), any()) } returns ByteArrayInputStream("".toByteArray())
            val result = client.listCommits(emptyMap(), emptyMap(), emptyList())
            result.size shouldBe 0
        }

        "list commits filters result" {
            every { client.getMetadataContent(any(), any()) } returns ByteArrayInputStream(
                    arrayOf("{\"id\":\"a\",\"properties\":{\"tags\":{\"c\":\"d\"}}}",
                            "{\"id\":\"b\",\"properties\":{}}")
                            .joinToString("\n").toByteArray())
            val result = client.listCommits(emptyMap(), emptyMap(), listOf("c" to null))
            result.size shouldBe 1
            result[0].first shouldBe "a"
        }
    }
}
