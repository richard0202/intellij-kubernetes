/*******************************************************************************
 * Copyright (c) 2022 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.intellij.kubernetes.console

import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CardLayoutPanel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SingleSelectionModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.redhat.devtools.intellij.kubernetes.model.IResourceModel
import com.redhat.devtools.intellij.kubernetes.model.util.ResourceException
import com.redhat.devtools.intellij.kubernetes.model.util.toMessage
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Pod
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

abstract class ConsoleTab<T : ConsoleView, W : Any?>(
    protected val pod: Pod,
    protected val model: IResourceModel,
    protected val project: Project
) : Disposable {

    protected val watches = emptyList<AtomicReference<W>>()
    protected val watch: AtomicReference<W?> = AtomicReference()
    private var consoles: CardLayoutPanel<Container, Container, ConsoleOrErrorPanel>? = null

    fun createComponent(): JComponent {
        val containers = createContainersList(::onContainerSelected)
        val scrollPane = JBScrollPane(containers)
        val consoles = ConsolesPanel()
        this.consoles = consoles
        val splitPane = createSplitPanel(scrollPane, consoles)
        containers.selectedIndex = 0
        return createToolWindowPanel(splitPane)
    }

    private fun createToolWindowPanel(splitPane: JComponent): SimpleToolWindowPanel {
        return SimpleToolWindowPanel(false, true).apply {
            setContent(splitPane)
            revalidate()
            repaint()
        }
    }

    private fun onContainerSelected(container: Container) {
        consoles?.select(container, true)
    }

    private fun createSplitPanel(left: JComponent, right: JComponent): JComponent {
        return OnePixelSplitter(false, 0.15f).apply {
            isShowDividerControls = true
            setHonorComponentsMinimumSize(true)
            firstComponent = left
            secondComponent = right
        }
    }

    private fun createContainersList(onSelected: (container: Container) -> Unit): JBList<*> {
        return JBList<ContainerLabelAdapter>().apply {
            addListSelectionListener { onSelected.invoke(selectedValue.container) }
            val listModel = CollectionListModel<ContainerLabelAdapter>()
            listModel.add(pod.spec.containers
                .map { container -> ContainerLabelAdapter(container) })
            model = listModel
            selectionModel = SingleSelectionModel()
        }
    }


    abstract fun createConsoleView(project: Project): T?

    abstract fun getDisplayName(): String

    protected abstract fun startWatch(container: Container?, consoleView: T?): W?

    private class ContainerLabelAdapter(val container: Container) {
        override fun toString(): String {
            // workaround: spaces to create left indent (insets/border don't affect selection insets)
            return "  " + container.name
        }
    }

    protected fun showError(container: Container, message: String) {
        val consoleOrErrorPanel = consoles?.getValue(container, false) ?: return
        consoleOrErrorPanel.showError(message)
    }

    private inner class ConsolesPanel : CardLayoutPanel<Container, Container, ConsoleOrErrorPanel>() {

        override fun prepare(container: Container): Container {
            return container
        }

        override fun create(container: Container): ConsoleOrErrorPanel {
            return ConsoleOrErrorPanel(container)
        }
    }

    private inner class ConsoleOrErrorPanel(private val container: Container): SimpleCardLayoutPanel<JComponent>() {

        private val NAME_VIEW_CONSOLE = "console"
        private val NAME_VIEW_ERROR = "error"

        private val consoleView by lazy {
            val view = createConsoleView(project)
            if (view != null) {
                asyncStartWatch(view)
            }
            view
        }

        private val errorView by lazy {
            ErrorView()
        }

        init {
            val consoleView = consoleView
            if (consoleView != null) {
                add(consoleView.component, NAME_VIEW_CONSOLE)
            }
            add(errorView.component, NAME_VIEW_ERROR)
            showConsole()
        }

        fun showConsole() {
            show(NAME_VIEW_CONSOLE)
        }

        fun showError(message: String) {
            errorView.setError(message) {
                showConsole()
                asyncStartWatch(consoleView)
            }
            show(NAME_VIEW_ERROR)
        }

        private fun asyncStartWatch(consoleView: T?) {
            if (consoleView == null) {
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    startWatch(container, consoleView)
                } catch (e: ResourceException) {
                    val message = toMessage(e)
                    logger<TerminalTab>().warn(message, e)
                    showError(message)
                }
            }
        }

    }

    private class ErrorView {
        val component by lazy {
            JPanel().apply {
                // workaround: failed to have error HyperlinkLabel centered using BorderLayout etc.
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(Box.createRigidArea(Dimension(100, 0)))
                add(label)
            }
        }

        private val label: HyperlinkLabel by lazy {
            HyperlinkLabel().apply {
                setIcon(AllIcons.General.Error)
                addHyperlinkListener {
                    listener?.invoke()
                }
            }
        }

        private var listener: (() -> Unit)? = null

        fun setError(message: String, listener: () -> Unit) {
            label.setHtmlText("$message <a>Reconnect.</a>")
            this.listener = listener
        }

    }
}