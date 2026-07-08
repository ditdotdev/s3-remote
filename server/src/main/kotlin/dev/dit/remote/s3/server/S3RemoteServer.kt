// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.remote.s3.server

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteServerUtil
import dev.dit.remote.archive.ArchiveRemote
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * The S3 provider is a very simple provider for storing whole commits directly in a S3 bucket. Each commit is is a
 * key within a folder, for example:
 *
 *      s3://bucket/path/to/repo/3583-4053-598ea-298fa
 *
 * Within each commit sub-directory, there is .tar.gz file for each volume. The metadata for each commit is stored
 * as metadata for the object, as well in a 'dit' file at the root of the repository, with once line per commit. We
 * do this for a few reasons:
 *
 *      * Storing it in object metdata is inefficient, as there's no way to fetch the metadata of multiple objects
 *        at once. We keep it per-object for the cases where we
 *      * We want to be able to access this data in a read-only fashion over the HTTP interface, and there is no way
 *        to access object metadata (or even necessarily iterate over objects) through the HTTP interface.
 *
 * This has its downsides, namely that deleting a commit is more complicated, and there is greater risk of
 * concurrent operations creating invalid state, but those are existing challenges with these simplistic providers.
 * Properly solving them would require a more sophisticated provider with server-side logic.
 */
class S3RemoteServer : ArchiveRemote() {
    // Canonical S3 user-metadata key under which per-commit metadata is written. Matches
    // the Kotlin Maven group / reverse-DNS "dev.dit".
    private val metadataProp = "dev.dit"

    // S3 user-metadata keys to try, in order, when reading per-commit metadata. The
    // canonical key is first, followed by legacy keys written before the dit rename
    // ("com.dit") and before that ("com.datadatdat"), so existing S3 remotes keep
    // working without a re-push.
    private val metadataPropKeys = listOf(metadataProp, "com.dit", "com.datadatdat")

    override fun getProvider(): String = "s3"

    /**
     * Validate a S3 remote. The only required field is "bucket". Optional fields include (path, accessKey,
     * secretKey, region). If either accessKey or secretKey is specified, then both must be specified.
     */
    override fun validateRemote(remote: Map<String, Any>): Map<String, Any> {
        util.validateFields(remote, listOf("bucket"), listOf("path", "accessKey", "secretKey", "region"))

        if ((!remote.containsKey("accessKey") && remote.containsKey("secretKey")) ||
            (remote.containsKey("accessKey") && !remote.containsKey("secretKey"))
        ) {
            throw IllegalArgumentException("Either both access key and secret key must be set, or neither")
        }

        return remote
    }

    /**
     * Validate S3 parameters. All parameters are optional: (accessKey, secretKey, region, sessionToken).
     */
    override fun validateParameters(parameters: Map<String, Any>?): Map<String, Any> {
        val params = parameters ?: emptyMap()
        util.validateFields(params, emptyList(), listOf("accessKey", "secretKey", "region", "sessionToken"))
        return params
    }

    /**
     * Get an instance of the S3 client based on the remote configuration and parameters. Public for testing purposes.
     *
     * The caller owns the returned [S3Client] and is responsible for closing it (e.g. via `use { ... }`). Every
     * internal use of this function wraps the result in `.use { ... }` for that reason.
     */
    fun getClient(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
    ): S3Client {
        val accessKey =
            (
                parameters.get("accessKey") ?: remote["accessKey"]
                    ?: throw IllegalArgumentException("missing access key")
            ) as String
        val secretKey =
            (
                parameters.get("secretKey") ?: remote["secretKey"]
                    ?: throw IllegalArgumentException("missing secret key")
            ) as String
        val region =
            (
                parameters.get("region") ?: remote["region"]
                    ?: throw IllegalArgumentException("missing region")
            ) as String

        val creds =
            if (parameters.containsKey("sessionToken")) {
                AwsSessionCredentials.create(accessKey, secretKey, parameters.get("sessionToken").toString())
            } else {
                AwsBasicCredentials.create(accessKey, secretKey)
            }
        val provider = StaticCredentialsProvider.create(creds)

        return S3Client
            .builder()
            .credentialsProvider(provider)
            .region(Region.of(region))
            .build()
    }

    /**
     * This function will return the (bucket, key) that identifies the given commit (or root key if no commit
     * is specified). This takes into the account the optional path configured in the remote. Public for testing.
     */
    fun getPath(
        remote: Map<String, Any>,
        commitId: String? = null,
    ): Pair<String, String?> {
        val key =
            if (remote["path"] == null) {
                commitId
            } else if (commitId == null) {
                remote["path"] as String
            } else {
                "${remote["path"]}/$commitId"
            }

        return Pair(remote["bucket"] as String, key)
    }

    /**
     * Gets the path to the dit repo metadata file, which is either in the root of the bucket (if the path is
     * null) or within the path directory.
     */
    internal fun getMetadataKey(key: String?): String =
        if (key == null) {
            "dit"
        } else {
            "$key/dit"
        }

    /**
     * Helper function that fetches the content of the metadata file as an input stream. Returns an empty file if
     * it doesn't yet exist.
     */
    internal fun getMetadataContent(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
    ): InputStream =
        getClient(remote, parameters).use { s3 ->
            try {
                val (bucket, key) = getPath(remote)
                val request =
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(getMetadataKey(key))
                        .build()
                val bytes = s3.getObject(request).use { it.readAllBytes() }
                ByteArrayInputStream(bytes)
            } catch (e: NoSuchKeyException) {
                ByteArrayInputStream("".toByteArray())
            } catch (e: S3Exception) {
                if (e.statusCode() == 404) {
                    ByteArrayInputStream("".toByteArray())
                } else {
                    throw e
                }
            }
        }

    /**
     * Get the metadata for a single commit. This is stored as a user property on the object under the
     * canonical key "dev.dit", with fallback to the legacy keys "com.dit" and "com.datadatdat" for
     * remotes pushed before the dit rename. For historical reasons, we keep the metadata within the
     * "properties" sub-object. This matches how it's stored in the top-level metadata file.
     */
    override fun getCommit(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        commitId: String,
    ): Map<String, Any>? {
        getClient(remote, parameters).use { s3 ->
            val (bucket, key) = getPath(remote, commitId)
            try {
                val request =
                    HeadObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build()
                val response = s3.headObject(request)
                val userMetadata = response.metadata()
                val metadataJson =
                    metadataPropKeys
                        .asSequence()
                        .mapNotNull { userMetadata?.get(it) }
                        .firstOrNull { it.isNotEmpty() }
                        ?: return null
                val metadata: Map<String, Any> = gson.fromJson(metadataJson, object : TypeToken<Map<String, Any>>() {}.type)

                if (!metadata.containsKey("properties")) {
                    return null
                }
                @Suppress("UNCHECKED_CAST")
                return metadata["properties"] as Map<String, Any>
            } catch (e: NoSuchKeyException) {
                return null
            } catch (e: S3Exception) {
                if (e.statusCode() == 404) {
                    return null
                }
                throw e
            }
        }
    }

    /**
     * List all commits in a repository. This operates by processing the metadata file at the root of the S3 path. Each
     * line is a JSON object with an "id" field and "properties" field.
     */
    override fun listCommits(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        tags: List<Pair<String, String?>>,
    ): List<Pair<String, Map<String, Any>>> {
        // `getMetadataContent` eagerly reads + closes the underlying S3 stream,
        // returning an in-memory stream. We still close it for hygiene, but avoid
        // Kotlin's `Closeable?.use` extension whose null-receiver branch can never
        // be exercised (we always pass non-null).
        val metadata = getMetadataContent(remote, parameters)
        val text =
            try {
                String(metadata.readBytes(), Charsets.UTF_8)
            } finally {
                metadata.close()
            }
        val lines = if (text.isEmpty()) emptyList() else text.split('\n')
        return util.sortDescending(
            lines.mapNotNull { line ->
                if (line.isEmpty()) {
                    null
                } else {
                    val result: Map<String, Any> = gson.fromJson(line, object : TypeToken<Map<String, Any>>() {}.type)
                    val id = result.get("id") as? String

                    @Suppress("UNCHECKED_CAST")
                    val properties = result.get("properties") as? Map<String, Any>
                    if (id != null && properties != null && util.matchTags(properties, tags)) {
                        id to properties
                    } else {
                        null
                    }
                }
            },
        )
    }

    internal fun appendMetadata(
        remote: Map<String, Any>,
        params: Map<String, Any>,
        json: String,
    ) {
        getClient(remote, params).use { s3 ->
            val (bucket, key) = getPath(remote)
            var length = 0L
            var currentMetadata: InputStream
            try {
                val request =
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(getMetadataKey(key))
                        .build()
                // Read the entire body eagerly inside `use` so the underlying HTTP connection
                // is always closed, even if `response()` or `contentLength()` throws.
                val bytes =
                    s3.getObject(request).use { responseStream ->
                        length = responseStream.response()?.contentLength() ?: 0L
                        responseStream.readAllBytes()
                    }
                currentMetadata = ByteArrayInputStream(bytes)
            } catch (e: NoSuchKeyException) {
                currentMetadata = ByteArrayInputStream("".toByteArray())
            } catch (e: S3Exception) {
                if (e.statusCode() == 404) {
                    currentMetadata = ByteArrayInputStream("".toByteArray())
                } else {
                    throw e
                }
            }

            val appendStream = ByteArrayInputStream("$json\n".toByteArray())
            val stream = SequenceInputStream(currentMetadata, appendStream)
            val totalLength = length + json.length + 1
            val request =
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(getMetadataKey(key))
                    .contentLength(totalLength)
                    .build()
            s3.putObject(request, RequestBody.fromInputStream(stream, totalLength))
        }
    }

    // There is no efficient way to do this, simply read all the commits, update the one in question, and upload
    internal fun updateMetadata(
        remote: Map<String, Any>,
        params: Map<String, Any>,
        commitId: String,
        commit: Map<String, Any>,
    ) {
        getClient(remote, params).use { s3 ->
            val (bucket, key) = getPath(remote)
            val originalCommits = listCommits(remote, params, emptyList())
            val metadata =
                originalCommits
                    .map {
                        if (it.first == commitId) {
                            gson.toJson(mapOf("id" to it.first, "properties" to commit))
                        } else {
                            gson.toJson(mapOf("id" to it.first, "properties" to it.second))
                        }
                    }.joinToString("\n") + "\n"

            val request =
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(getMetadataKey(key))
                    .build()
            s3.putObject(request, RequestBody.fromString(metadata))
        }
    }

    class S3Operation(
        provider: S3RemoteServer,
        operation: RemoteOperation,
    ) : Closeable {
        val s3: S3Client
        val bucket: String
        val key: String?

        init {
            s3 = provider.getClient(operation.remote, operation.parameters)
            val path = provider.getPath(operation.remote, operation.commitId)
            bucket = path.first
            key = path.second
        }

        override fun close() {
            s3.close()
        }
    }

    override fun syncDataStart(operation: RemoteOperation): Any? = S3Operation(this, operation)

    override fun syncDataEnd(
        operation: RemoteOperation,
        operationData: Any?,
        isSuccessful: Boolean,
    ) {
        (operationData as? S3Operation)?.close()
    }

    override fun pullArchive(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
        archive: File,
    ) {
        val data = operationData as S3Operation
        val request =
            GetObjectRequest
                .builder()
                .bucket(data.bucket)
                .key("${data.key}/$volume.tar.gz")
                .build()
        val obj = data.s3.getObject(request)
        obj.use { input ->
            archive.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    override fun pushArchive(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
        archive: File,
    ) {
        val data = operationData as S3Operation
        val request =
            PutObjectRequest
                .builder()
                .bucket(data.bucket)
                .key("${data.key}/$volume.tar.gz")
                .build()
        data.s3.putObject(request, RequestBody.fromFile(archive))
    }

    override fun pushMetadata(
        operation: RemoteOperation,
        commit: Map<String, Any>,
        isUpdate: Boolean,
    ) {
        S3Operation(this, operation).use { data ->
            val json = gson.toJson(mapOf("id" to operation.commitId, "properties" to commit))
            val userMetadata = mapOf(metadataProp to json)
            val request =
                PutObjectRequest
                    .builder()
                    .bucket(data.bucket)
                    .key(data.key)
                    .metadata(userMetadata)
                    .contentLength(0)
                    .build()
            data.s3.putObject(request, RequestBody.fromBytes(ByteArray(0)))

            if (isUpdate) {
                updateMetadata(operation.remote, operation.parameters, operation.commitId, commit)
            } else {
                appendMetadata(operation.remote, operation.parameters, json)
            }
        }
    }

    // `gson` and `util` are stateless utilities; hoisting them to the companion
    // object avoids the latent bug of mutable per-instance state and the small
    // per-instance allocation overhead.
    companion object {
        internal val gson: com.google.gson.Gson = GsonBuilder().create()
        internal val util: RemoteServerUtil = RemoteServerUtil()
    }
}
