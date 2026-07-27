package dev.omniapi

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import dev.omniapi.server.ServerManager
import dev.omniapi.state.ApiState
import dev.omniapi.ui.ApiGuiPanel
import javax.swing.SwingUtilities

class OmniApiExtension : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("Burp OmniAPI")

        val state = ApiState(initialPort = 31337, initialBindAddress = "127.0.0.1")
        val manager = ServerManager(api, state)
        api.extension().registerUnloadingHandler(OmniApiUnloadHandler(manager))

        SwingUtilities.invokeLater {
            val panel = ApiGuiPanel(manager, api.userInterface().swingUtils().suiteFrame())
            api.userInterface().applyThemeToComponent(panel)
            api.userInterface().registerSuiteTab("OmniAPI", panel)
        }

        manager.startAsync()
        api.logging().logToOutput("Burp OmniAPI ${dev.omniapi.handler.SystemHandler.VERSION} initialized")
    }
}
