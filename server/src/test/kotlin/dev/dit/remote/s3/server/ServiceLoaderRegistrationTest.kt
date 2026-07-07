// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.remote.s3.server

import dev.dit.remote.RemoteServer
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import java.util.ServiceLoader

/**
 * Guards the Java ServiceLoader registration. The META-INF/services file MUST be
 * named after the SPI interface FQCN (dev.dit.remote.RemoteServer) and list this
 * implementation, or dit-server will not discover the provider at runtime. A
 * package rename (e.g. com.datadatdat -> dev.dit) silently breaks the file name
 * or its contents; compilation and ktlint do not catch it. This does.
 */
class ServiceLoaderRegistrationTest :
    StringSpec({
        "S3RemoteServer is discoverable as a dev.dit.remote.RemoteServer ServiceLoader provider" {
            ServiceLoader.load(RemoteServer::class.java).any { it is S3RemoteServer } shouldBe true
        }
    })
