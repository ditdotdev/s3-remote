/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.s3.client

import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.extensions.system.OverrideMode
import io.kotlintest.extensions.system.withEnvironment
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.net.URI
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

class S3RemoteClientTest : StringSpec() {

    var client = S3RemoteClient()

    override fun testCaseOrder() = TestCaseOrder.Random

    override fun afterTest(testCase: TestCase, result: TestResult) {
        clearAllMocks()
    }

    init {
        "get provider returns s3" {
            client.getProvider() shouldBe "s3"
        }

        "parsing full S3 URI succeeds" {
            val result = client.parseUri(URI("s3://bucket/object/path"), emptyMap())
            result["bucket"] shouldBe "bucket"
            result["path"] shouldBe "object/path"
            result["accessKey"] shouldBe null
            result["secretKey"] shouldBe null
        }

        "parsing S3 without path succeeds" {
            val result = client.parseUri(URI("s3://bucket"), emptyMap())
            result["bucket"] shouldBe "bucket"
            result["path"] shouldBe null
            result["accessKey"] shouldBe null
            result["secretKey"] shouldBe null
        }

        "specifying an invalid property fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3://bucket/path"), mapOf("foo" to "bar"))
            }
        }

        "plain s3 provider fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3"), emptyMap())
            }
        }

        "specifying user fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3://user@bucket/path"), emptyMap())
            }
        }

        "specifying password fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3://user:password@bucket/path"), emptyMap())
            }
        }

        "specifying port fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3://bucket:80/path"), emptyMap())
            }
        }

        "missing bucket in s3 URI fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3:///path"), emptyMap())
            }
        }

        "specifying additional properties succeeds" {
            val result = client.parseUri(URI("s3://bucket/object/path"),
                    mapOf("accessKey" to "ACCESS", "secretKey" to "SECRET", "region" to "REGION"))
            result["bucket"] shouldBe "bucket"
            result["path"] shouldBe "object/path"
            result["accessKey"] shouldBe "ACCESS"
            result["secretKey"] shouldBe "SECRET"
            result["region"] shouldBe "REGION"
        }

        "specifying access key only fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3://bucket/object/path"), mapOf("accessKey" to "ACCESS"))
            }
        }

        "specifying secret key only fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3://bucket/object/path"), mapOf("secretKey" to "SECRET"))
            }
        }

        "s3 remote to URI succeeds" {
            val (uri, props) = client.toUri(mapOf("bucket" to "bucket", "path" to "path"))
            uri shouldBe "s3://bucket/path"
            props.size shouldBe 0
        }

        "s3 remote with keys to URI succeeds" {
            val (uri, props) = client.toUri(mapOf("bucket" to "bucket", "path" to "path",
                    "accessKey" to "ACCESS", "secretKey" to "SECRET"))
            uri shouldBe "s3://bucket/path"
            props.size shouldBe 2
            props["accessKey"] shouldBe "ACCESS"
            props["secretKey"] shouldBe "*****"
        }

        "s3 remote with region to URI succeeds" {
            val (uri, props) = client.toUri(mapOf("bucket" to "bucket", "path" to "path",
                    "region" to "REGION"))
            uri shouldBe "s3://bucket/path"
            props.size shouldBe 1
            props["region"] shouldBe "REGION"
        }

        "s3 get parameters succeeds" {
            val params = client.getParameters(mapOf("bucket" to "bucket", "path" to "path",
                    "accessKey" to "ACCESS", "secretKey" to "SECRET", "region" to "REGION"))
            params["accessKey"] shouldBe "ACCESS"
            params["secretKey"] shouldBe "SECRET"
            params["region"] shouldBe "REGION"
        }

        "getting credentials from environment succeeds" {
            withEnvironment(mapOf("AWS_ACCESS_KEY_ID" to "accessKey", "AWS_SECRET_ACCESS_KEY" to "secretKey",
                    "AWS_REGION" to "us-west-2", "AWS_SESSION_TOKEN" to "sessionToken"), OverrideMode.SetOrOverride) {
                System.getenv("AWS_ACCESS_KEY_ID") shouldBe "accessKey"
                System.getenv("AWS_SECRET_ACCESS_KEY") shouldBe "secretKey"
                System.getenv("AWS_REGION") shouldBe "us-west-2"
                System.getenv("AWS_SESSION_TOKEN") shouldBe "sessionToken"
                val params = client.getParameters(mapOf("bucket" to "bucket", "path" to "path"))
                params["accessKey"] shouldBe "accessKey"
                params["secretKey"] shouldBe "secretKey"
                params["sessionToken"] shouldBe "sessionToken"
                params["region"] shouldBe "us-west-2"
            }
        }

        "failure to resolve AWS credentials fails" {
            mockkStatic(DefaultCredentialsProvider::class)
            val credentialsProvider = mockk<DefaultCredentialsProvider>()
            every { DefaultCredentialsProvider.create() } returns credentialsProvider
            every { credentialsProvider.resolveCredentials() } returns null
            shouldThrow<IllegalArgumentException> {
                client.getParameters(mapOf("bucket" to "bucket", "path" to "path"))
            }
        }

        "AWS credentials without access key fails" {
            mockkStatic(DefaultCredentialsProvider::class)
            val credentialsProvider = mockk<DefaultCredentialsProvider>()
            every { DefaultCredentialsProvider.create() } returns credentialsProvider
            val credentials = mockk<AwsCredentials>()
            every { credentialsProvider.resolveCredentials() } returns credentials
            every { credentials.accessKeyId() } returns null
            every { credentials.secretAccessKey() } returns null
            shouldThrow<IllegalArgumentException> {
                client.getParameters(mapOf("bucket" to "bucket", "path" to "path"))
            }
        }
    }
}
