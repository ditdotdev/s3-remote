package io.titandata.remote.s3.server

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.titandata.remote.RemoteOperation
import io.titandata.remote.RemoteServer
import io.titandata.remote.RemoteServerUtil
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The S3 provider is a very simple provider for storing whole commits directly in a S3 bucket. Each commit is is a
 * key within a folder, for example:
 *
 *      s3://bucket/path/to/repo/3583-4053-598ea-298fa
 *
 * Within each commit sub-directory, there is .tar.gz file for each volume. The metadata for each commit is stored
 * as metadata for the object, as well in a 'titan' file at the root of the repository, with once line per commit. We
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
class S3RemoteServer : RemoteServer {

    private val METADATA_PROP = "io.titan-data"
    internal val gson = GsonBuilder().create()
    internal val util = RemoteServerUtil()

    override fun getProvider(): String {
        return "s3"
    }

    /**
     * Validate a S3 remote. The only required field is "bucket". Optional fields include (path, accessKey,
     * secretKey, region). If either accessKey or secretKey is specified, then both must be specified.
     */
    override fun validateRemote(remote: Map<String, Any>): Map<String, Any> {
        util.validateFields(remote, listOf("bucket"), listOf("path", "accessKey", "secretKey", "region"))

        if ((!remote.containsKey("accessKey") && remote.containsKey("secretKey")) ||
                (remote.containsKey("accessKey") && !remote.containsKey("secretKey"))) {
            throw IllegalArgumentException("Either both access key and secret key must be set, or neither")
        }

        return remote
    }

    /**
     * Validate S3 parameters. All parameters are optional: (accessKey, secretKey, region, sessionToken).
     */
    override fun validateParameters(parameters: Map<String, Any>): Map<String, Any> {
        util.validateFields(parameters, emptyList(), listOf("accessKey", "secretKey", "region", "sessionToken"))
        return parameters
    }

    /**
     * Get an instance of the S3 client based on the remote configuration and parameters.
     */
    fun getClient(remote: Map<String, Any>, parameters: Map<String, Any>): AmazonS3 {
        val accessKey = (parameters.get("accessKey") ?: remote["accessKey"]
            ?: throw IllegalArgumentException("missing access key")) as String
        val secretKey = (parameters.get("secretKey") ?: remote["secretKey"]
            ?: throw IllegalArgumentException("missing secret key")) as String
        val region = (parameters.get("region") ?: remote["region"]
            ?: throw IllegalArgumentException("missing region")) as String

        val creds = if (parameters.containsKey("sessionToken")) {
            BasicSessionCredentials(accessKey, secretKey, parameters.get("sessionToken").toString())
        } else {
            BasicAWSCredentials(accessKey, secretKey)
        }
        val provider = AWSStaticCredentialsProvider(creds)

        return AmazonS3ClientBuilder.standard().withCredentials(provider).withRegion(region).build()!!
    }

    /**
     * This function will return the (bucket, key) that identifies the given commit (or root key if no commit
     * is specified). This takes into the account the optional path configured in the remote.
     */
    internal fun getPath(remote: Map<String, Any>, commitId: String? = null): Pair<String, String?> {
        val key = if (remote["path"] == null) {
            commitId
        } else if (commitId == null) {
            remote["path"] as String
        } else {
            "${remote["path"]}/$commitId"
        }

        return Pair(remote["bucket"] as String, key)
    }

    /**
     * Gets the path to the titan repo metadata file, which is either in the root of the bucket (if the path is
     * null) or within the path directory.
     */
    internal fun getMetadataKey(key: String?): String {
        return if (key == null) {
            "titan"
        } else {
            "$key/titan"
        }
    }

    /**
     * Helper function that fetches the content of the metadata file as an input stream. Returns an empty file if
     * it doesn't yet exist.
     */
    internal fun getMetadataContent(remote: Map<String, Any>, parameters: Map<String, Any>): InputStream {
        val s3 = getClient(remote, parameters)
        val (bucket, key) = getPath(remote)

        try {
            return s3.getObject(bucket, getMetadataKey(key)).objectContent
        } catch (e: AmazonS3Exception) {
            if (e.statusCode == 404) {
                return ByteArrayInputStream("".toByteArray())
            } else {
                throw e
            }
        }
    }

    /**
     * Get the metadata for a single commit. This is stored as a user property on the object with the key
     * "io.titan-data". For historical reasons, we keep the metadata within the "properties" sub-object. This
     * matches how it's stored in the top-level metadata file.
     */
    override fun getCommit(remote: Map<String, Any>, parameters: Map<String, Any>, commitId: String): Map<String, Any>? {
        val s3 = getClient(remote, parameters)
        val (bucket, key) = getPath(remote, commitId)
        try {
            val obj = s3.getObjectMetadata(bucket, key)
            if (obj.userMetadata == null || !obj.userMetadata.containsKey(METADATA_PROP)) {
                return null
            }
            val metadata: Map<String, Any> = gson.fromJson(obj.userMetadata[METADATA_PROP], object : TypeToken<Map<String, Any>>() {}.type)

            if (!metadata.containsKey("properties")) {
                return null
            }
            @Suppress("UNCHECKED_CAST")
            return metadata["properties"] as Map<String, Any>
        } catch (e: AmazonS3Exception) {
            if (e.statusCode == 404) {
                return null
            }
            throw e
        }
    }

    /**
     * List all commits in a repository. This operates by processing the metadata file at the root of the S3 path. Each
     * line is a JSON object with an "id" field and "properties" field.
     */
    override fun listCommits(remote: Map<String, Any>, parameters: Map<String, Any>, tags: List<Pair<String, String?>>): List<Pair<String, Map<String, Any>>> {
        val ret = mutableListOf<Pair<String, Map<String, Any>>>()
        val metadata = getMetadataContent(remote, parameters)

        try {
            for (line in metadata.bufferedReader().lines()) {
                if (line != "") {
                    val result: Map<String, Any> = gson.fromJson(line, object : TypeToken<Map<String, Any>>() {}.type)
                    val id = result.get("id")
                    val properties = result.get("properties")
                    if (id != null && properties != null) {
                        id as String
                        @Suppress("UNCHECKED_CAST")
                        properties as Map<String, Any>
                        if (util.matchTags(properties, tags)) {
                            ret.add(id to properties)
                        }
                    }
                }
            }
        } finally {
            metadata.close()
        }

        return util.sortDescending(ret)
    }

    override fun endOperation(operation: RemoteOperation, isSuccessful: Boolean) {
        throw NotImplementedError()
    }

    override fun startOperation(operation: RemoteOperation) {
        throw NotImplementedError()
    }

    override fun syncVolume(operation: RemoteOperation, volumeName: String, volumeDescription: String, volumePath: String, scratchPath: String) {
        throw NotImplementedError()
    }
}
