package dev.omniapi.ui

import dev.omniapi.server.ServerManager
import dev.omniapi.state.ApiModule
import dev.omniapi.state.ServerStatus
import dev.omniapi.util.Validation
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.InetAddress
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

class ApiGuiPanel(
    private val manager: ServerManager,
    private val dialogParent: Component
) : JPanel(BorderLayout(12, 12)) {
    private val state = manager.state
    private val keyField = JPasswordField(state.apiKey(), 48)
    private val bindField = JTextField(state.bindAddress.get(), 18)
    private val portField = JTextField(state.port.get().toString(), 8)
    private val runningToggle = JCheckBox("Server running")
    private val statusLabel = JLabel()
    private val errorArea = JTextArea(2, 60)
    private val usageArea = JTextArea(8, 80)

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
        content.add(section("Server", serverPanel()), constraints)
        constraints.gridy++
        content.add(section("Modules", modulesPanel()), constraints)
        constraints.gridy++
        content.add(section("Using the API key", usagePanel()), constraints)
        constraints.gridy++
        constraints.weighty = 1.0
        constraints.anchor = GridBagConstraints.NORTHWEST
        content.add(JPanel(), constraints)

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
            addActionListener {
                keyField.text = state.regenerateKey()
                refreshUsage()
                JOptionPane.showMessageDialog(
                    dialogParent,
                    "The previous API key was invalidated immediately.",
                    "OmniAPI",
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
        c.gridx = 0
        c.gridy = 1
        add(runningToggle.apply {
            addActionListener {
                if (isSelected) manager.startAsync() else manager.stopAsync()
                refreshStatus()
            }
        }, c)
        c.gridx = 1
        c.gridwidth = 4
        add(statusLabel, c)
        c.gridx = 0
        c.gridy = 2
        c.gridwidth = 5
        c.fill = GridBagConstraints.HORIZONTAL
        add(errorArea, c)
    }

    private fun modulesPanel(): JPanel = JPanel(GridBagLayout()).apply {
        ApiModule.entries.forEachIndexed { index, module ->
            val checkBox = JCheckBox(module.displayName(), state.isEnabled(module)).apply {
                addActionListener { state.setEnabled(module, isSelected) }
                toolTipText = "Immediately allow or block ${module.path} endpoints"
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

    private fun usagePanel(): JPanel = JPanel(BorderLayout(6, 6)).apply {
        add(usageArea, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Copy Browser GET URL").apply {
                toolTipText = "Copies a browser-ready system information URL containing the current API key"
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection("${baseUrl()}/system/info?apiKey=${state.apiKey()}"),
                        null
                    )
                }
            })
            add(JButton("Copy curl Example").apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection(
                            "curl -H \"X-API-Key: ${state.apiKey()}\" ${baseUrl()}/system/info"
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
                    "This exposes OmniAPI beyond loopback without built-in TLS. " +
                        "Only continue when protected by trusted network controls and TLS termination.",
                    "Confirm non-loopback OmniAPI binding",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                )
                if (choice != JOptionPane.YES_OPTION) return
            }
            manager.restartAsync(bindAddress, port)
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(dialogParent, e.message, "Invalid OmniAPI configuration", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun refreshStatus() {
        val status = state.serverStatus.get()
        runningToggle.isSelected = status in setOf(ServerStatus.RUNNING, ServerStatus.STARTING)
        runningToggle.isEnabled = status !in setOf(ServerStatus.STARTING, ServerStatus.STOPPING)
        statusLabel.text = when (status) {
            ServerStatus.RUNNING -> "Listening at http://${state.bindAddress.get()}:${state.port.get()}"
            ServerStatus.STARTING -> "Starting…"
            ServerStatus.STOPPING -> "Stopping…"
            ServerStatus.STOPPED -> "Stopped"
            ServerStatus.FAILED -> "Failed"
        }
        errorArea.text = when {
            status == ServerStatus.FAILED -> state.lastError.get().orEmpty()
            status == ServerStatus.RUNNING && state.bindAddress.get() !in setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1") ->
                "Warning: OmniAPI is exposed beyond loopback. Use trusted network controls and TLS termination."
            else -> ""
        }
        refreshUsage()
    }

    private fun refreshUsage() {
        usageArea.text = """
            Header authentication (recommended; works with every endpoint):
            curl -H "X-API-Key: YOUR_API_KEY" ${baseUrl()}/system/info

            Browser GET:
            ${baseUrl()}/system/info?apiKey=YOUR_API_KEY

            Swagger UI: ${baseUrl()}/swagger

            Query parameters can remain in browser history and access logs. Prefer the header form, and use browser URLs only on a trusted loopback connection.
        """.trimIndent()
        usageArea.caretPosition = 0
    }

    private fun baseUrl(): String = "http://${state.bindAddress.get()}:${state.port.get()}"

    private fun ApiModule.displayName(): String = when (this) {
        ApiModule.BAMBDAS -> "Bambda"
        ApiModule.WEBSOCKETS -> "WebSockets"
        else -> name.lowercase().replaceFirstChar(Char::uppercase)
    }
}
