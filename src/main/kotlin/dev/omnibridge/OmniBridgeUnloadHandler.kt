package dev.omnibridge

import burp.api.montoya.extension.ExtensionUnloadingHandler
import dev.omnibridge.server.ServerManager
import java.util.concurrent.atomic.AtomicBoolean

class OmniBridgeUnloadHandler(
    private val serverManager: ServerManager
) : ExtensionUnloadingHandler {
    private val unloaded = AtomicBoolean(false)

    override fun extensionUnloaded() {
        if (unloaded.compareAndSet(false, true)) {
            serverManager.close()
        }
    }
}
