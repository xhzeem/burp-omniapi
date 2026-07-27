package dev.omniapi

import burp.api.montoya.extension.ExtensionUnloadingHandler
import dev.omniapi.server.ServerManager
import java.util.concurrent.atomic.AtomicBoolean

class OmniApiUnloadHandler(
    private val serverManager: ServerManager
) : ExtensionUnloadingHandler {
    private val unloaded = AtomicBoolean(false)

    override fun extensionUnloaded() {
        if (unloaded.compareAndSet(false, true)) {
            serverManager.close()
        }
    }
}
