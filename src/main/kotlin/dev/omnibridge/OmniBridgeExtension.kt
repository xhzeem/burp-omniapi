package dev.omnibridge

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import dev.omnibridge.server.ServerManager
import dev.omnibridge.state.ApiState
import dev.omnibridge.ui.OmniBridgeGuiPanel
import javax.swing.SwingUtilities

class OmniBridgeExtension : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("Burp OmniBridge")

        val state = ApiState(initialPort = 31337, initialBindAddress = "127.0.0.1")
        val manager = ServerManager(api, state)
        api.extension().registerUnloadingHandler(OmniBridgeUnloadHandler(manager))

        SwingUtilities.invokeLater {
            val panel = OmniBridgeGuiPanel(manager, api.userInterface().swingUtils().suiteFrame())
            api.userInterface().applyThemeToComponent(panel)
            api.userInterface().registerSuiteTab("OmniBridge", panel)
        }

        manager.startAsync()
        api.logging().logToOutput("Burp OmniBridge ${dev.omnibridge.handler.SystemHandler.VERSION} initialized")
    }
}
