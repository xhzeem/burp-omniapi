package dev.omnibridge.ui

import dev.omnibridge.server.ServerManager
import dev.omnibridge.state.ApiModule
import dev.omnibridge.state.ServerStatus
import dev.omnibridge.util.Validation
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.InetAddress
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager

class OmniBridgeGuiPanel(
    private val manager: ServerManager,
    private val dialogParent: Component
) : JPanel(BorderLayout(12, 12)) {
    private val state = manager.state
    private val keyField = JPasswordField(state.apiKey(), 38)
    private val bindField = JTextField(state.bindAddress.get(), 18)
    private val portField = JTextField(state.port.get().toString(), 8)
    private val serverToggleButton = JButton("Server Off")
    private val restToggle = JCheckBox("Enable REST API", state.restEnabled.get())
    private val mcpToggle = JCheckBox("Enable MCP", state.mcpEnabled.get())
    private val configEditingToggle =
        JCheckBox(
            "Allow REST API and MCP to read and edit project and user configuration",
            state.configEditingEnabled.get()
        )
    private val errorArea = JTextArea(2, 48)
    private val usageArea = JTextArea(8, 64)

    init {
        border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
        keyField.isEditable = false
        errorArea.isEditable = false
        errorArea.lineWrap = true
        errorArea.wrapStyleWord = true
        errorArea.isOpaque = false
        usageArea.isEditable = false
        usageArea.lineWrap = true
        usageArea.wrapStyleWord = true
        usageArea.background = UIManager.getColor("Panel.background")
        usageArea.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)

        val content = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(5, 5, 5, 5)
            gridx = 0
            gridy = 0
        }

        content.add(section("API key", apiKeyPanel()), constraints)
        constraints.gridy++
        content.add(section("Server & interfaces", serverPanel()), constraints)
        constraints.gridy++
        content.add(section("Burp configuration", advancedConfigurationPanel()), constraints)
        constraints.gridy++
        content.add(section("Modules", modulesPanel()), constraints)
        constraints.gridy++
        content.add(section("Using OmniBridge", usagePanel()), constraints)
        constraints.gridy++
        constraints.weighty = 1.0
        constraints.anchor = GridBagConstraints.NORTHWEST
        content.add(JPanel(), constraints)
        content.add(JPanel(), GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            gridheight = GridBagConstraints.REMAINDER
            weightx = 0.25
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })

        add(JScrollPane(content).apply { border = null }, BorderLayout.CENTER)
        manager.onStatusChanged { SwingUtilities.invokeLater { refreshStatus() } }
        refreshStatus()
    }

    private fun apiKeyPanel(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JLabel("API key:"))
        add(keyField)
        add(JButton("Copy").apply {
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(state.apiKey()), null)
            }
        })
        add(JButton("Regenerate Key").apply {
            applyBurpOrangeStyle()
            addActionListener {
                keyField.text = state.regenerateKey()
                refreshUsage()
                JOptionPane.showMessageDialog(
                    dialogParent,
                    "The previous API key was invalidated immediately.",
                    "OmniBridge",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        })
    }

    private fun serverPanel(): JPanel = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(3, 4, 3, 4)
        }
        add(JLabel("Bind address:"), c)
        c.gridx = 1
        add(bindField, c)
        c.gridx = 2
        add(JLabel("Port:"), c)
        c.gridx = 3
        add(portField, c)
        c.gridx = 4
        add(JButton("Apply & Restart").apply {
            addActionListener { applyAndRestart() }
        }, c)
        c.gridx = 5
        add(serverToggleButton.apply {
            applyServerToggleStyle()
            addActionListener {
                if (state.serverStatus.get() in setOf(ServerStatus.RUNNING, ServerStatus.STARTING)) {
                    manager.stopAsync()
                } else {
                    manager.startAsync()
                }
                refreshStatus()
            }
        }, c)
        c.gridx = 0
        c.gridy = 1
        c.gridwidth = 1
        add(restToggle.apply {
            toolTipText = "Expose the versioned REST API and Swagger documentation"
            addActionListener {
                state.restEnabled.set(isSelected)
                refreshUsage()
            }
        }, c)
        c.gridx = 1
        c.gridwidth = 5
        add(JLabel("${baseUrl()}/api/v1"), c)
        c.gridx = 0
        c.gridy = 2
        c.gridwidth = 1
        add(mcpToggle.apply {
            toolTipText = "Expose the MCP Streamable HTTP endpoint"
            addActionListener {
                state.mcpEnabled.set(isSelected)
                refreshUsage()
            }
        }, c)
        c.gridx = 1
        c.gridwidth = 5
        add(JLabel("${baseUrl()}/mcp"), c)
        c.gridx = 0
        c.gridy = 3
        c.gridwidth = 6
        c.fill = GridBagConstraints.HORIZONTAL
        add(errorArea, c)
    }

    private fun modulesPanel(): JPanel = JPanel(GridBagLayout()).apply {
        ApiModule.entries.forEachIndexed { index, module ->
            val checkBox = JCheckBox(module.displayName(), state.isEnabled(module)).apply {
                addActionListener { state.setEnabled(module, isSelected) }
                toolTipText = "Immediately allow or block this module in both REST and MCP"
            }
            add(checkBox, GridBagConstraints().apply {
                gridx = index % 3
                gridy = index / 3
                anchor = GridBagConstraints.WEST
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 8, 4, 8)
            })
        }
    }

    private fun advancedConfigurationPanel(): JPanel = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(4, 8, 4, 8)
        }
        add(configEditingToggle.apply {
            toolTipText = "Allow authenticated clients to read and change Burp project and user configuration"
            addActionListener { state.configEditingEnabled.set(isSelected) }
        }, c)
        c.gridy = 1
        add(JLabel("WARNING: Configuration changes can execute code.").apply {
            foreground = BURP_ORANGE
        }, c)
    }

    private fun usagePanel(): JPanel = JPanel(BorderLayout(6, 6)).apply {
        add(usageArea, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Open Swagger UI").apply {
                addActionListener {
                    runCatching { Desktop.getDesktop().browse(URI("${restBaseUrl()}/swagger")) }
                        .onFailure {
                            JOptionPane.showMessageDialog(
                                dialogParent,
                                "Open ${restBaseUrl()}/swagger in your browser.",
                                "Swagger UI",
                                JOptionPane.INFORMATION_MESSAGE
                            )
                        }
                }
            })
            add(JButton("Copy MCP Connection").apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection(
                            """
                            URL: ${baseUrl()}/mcp
                            X-API-Key: ${state.apiKey()}
                            """.trimIndent()
                        ),
                        null
                    )
                }
            })
        }, BorderLayout.SOUTH)
    }

    private fun section(title: String, panel: JPanel): JPanel =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(title)
            add(panel, BorderLayout.CENTER)
        }

    private fun applyAndRestart() {
        try {
            val port = portField.text.trim().toIntOrNull() ?: throw IllegalArgumentException("Port must be a number")
            val bindAddress = Validation.bindAddress(bindField.text)
            val address = InetAddress.getByName(bindAddress)
            if (!address.isLoopbackAddress) {
                val choice = JOptionPane.showConfirmDialog(
                    dialogParent,
                    "This exposes OmniBridge beyond loopback without built-in TLS. " +
                        "Only continue when protected by trusted network controls and TLS termination.",
                    "Confirm non-loopback OmniBridge binding",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                )
                if (choice != JOptionPane.YES_OPTION) return
            }
            manager.restartAsync(bindAddress, port)
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(dialogParent, e.message, "Invalid OmniBridge configuration", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun refreshStatus() {
        val status = state.serverStatus.get()
        serverToggleButton.text =
            if (status in setOf(ServerStatus.RUNNING, ServerStatus.STARTING)) "Server On" else "Server Off"
        serverToggleButton.isEnabled = status !in setOf(ServerStatus.STARTING, ServerStatus.STOPPING)
        updateServerToggleColors()
        errorArea.text = when {
            status == ServerStatus.FAILED -> state.lastError.get().orEmpty()
            status == ServerStatus.RUNNING && state.bindAddress.get() !in setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1") ->
                "Warning: OmniBridge is exposed beyond loopback. Use trusted network controls and TLS termination."
            else -> ""
        }
        errorArea.isVisible = errorArea.text.isNotBlank()
        refreshUsage()
    }

    private fun refreshUsage() {
        usageArea.text = """
            REST API
            Swagger: ${restBaseUrl()}/swagger

            MCP
            1. Choose Streamable HTTP in your MCP client.
            2. Use ${baseUrl()}/mcp
            3. Add the X-API-Key header using the key shown above.
        """.trimIndent()
        usageArea.caretPosition = 0
    }

    private fun baseUrl(): String = "http://${state.bindAddress.get()}:${state.port.get()}"
    private fun restBaseUrl(): String = "${baseUrl()}/api/v1"

    private fun JButton.applyBurpOrangeStyle() {
        background = BURP_ORANGE
        foreground = Color.WHITE
        isOpaque = true
        isContentAreaFilled = true
        isBorderPainted = true
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) {
                if (isEnabled) background = BURP_ORANGE_HOVER
            }

            override fun mouseExited(event: MouseEvent) {
                background = BURP_ORANGE
            }
        })
    }

    private fun JButton.applyServerToggleStyle() {
        foreground = Color.WHITE
        isOpaque = true
        isContentAreaFilled = true
        isBorderPainted = true
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) {
                if (isEnabled) {
                    background =
                        if (state.serverStatus.get() in setOf(ServerStatus.RUNNING, ServerStatus.STARTING)) {
                            BURP_ORANGE_HOVER
                        } else {
                            SERVER_OFF_HOVER
                        }
                }
            }

            override fun mouseExited(event: MouseEvent) {
                updateServerToggleColors()
            }
        })
        updateServerToggleColors()
    }

    private fun updateServerToggleColors() {
        serverToggleButton.background =
            if (state.serverStatus.get() in setOf(ServerStatus.RUNNING, ServerStatus.STARTING)) {
                BURP_ORANGE
            } else {
                SERVER_OFF
            }
    }

    private fun ApiModule.displayName(): String = when (this) {
        ApiModule.BAMBDAS -> "Bambda"
        ApiModule.WEBSOCKETS -> "WebSockets"
        else -> name.lowercase().replaceFirstChar(Char::uppercase)
    }

    companion object {
        private val BURP_ORANGE = Color(0xFF, 0x66, 0x33)
        private val BURP_ORANGE_HOVER = Color(0xF2, 0x5F, 0x30)
        private val SERVER_OFF = Color(0x6B, 0x6B, 0x6B)
        private val SERVER_OFF_HOVER = Color(0x60, 0x60, 0x60)
    }
}
