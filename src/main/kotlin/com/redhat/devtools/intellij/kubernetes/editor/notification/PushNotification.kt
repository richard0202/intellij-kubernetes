/*******************************************************************************
 * Copyright (c) 2021 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.intellij.kubernetes.editor.notification

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.EditorNotificationPanel
import com.redhat.devtools.intellij.kubernetes.editor.actions.DiffAction
import com.redhat.devtools.intellij.kubernetes.editor.actions.PullAction
import com.redhat.devtools.intellij.kubernetes.editor.actions.PushAction
import com.redhat.devtools.intellij.kubernetes.editor.hideNotification
import com.redhat.devtools.intellij.kubernetes.editor.showNotification
import javax.swing.JComponent

/**
 * An editor (panel) notification that informs of a change in the editor that may be pushed to the cluster.
 */
class PushNotification(private val editor: FileEditor, private val project: Project) {

    companion object {
        val KEY_PANEL = Key<JComponent>(PushNotification::class.java.canonicalName)
    }

    fun show(existsOnCluster: Boolean, isOutdated: Boolean) {
        editor.showNotification(KEY_PANEL, { createPanel(editor, project, existsOnCluster, isOutdated) }, project)
    }

    fun hide() {
        editor.hideNotification(KEY_PANEL, project)
    }

    private fun createPanel(editor: FileEditor, project: Project, existsOnCluster: Boolean, isOutdated: Boolean): EditorNotificationPanel {
        val panel = EditorNotificationPanel()
        panel.setText(
            "Push local changes, ${
                if (!existsOnCluster) {
                    "create new"
                } else {
                    "update existing"
                }
            } resource on cluster?"
        )
        panel.createActionLabel("Push", PushAction.ID)
        if (isOutdated) {
            panel.createActionLabel("Pull", PullAction.ID)
        }
        if (existsOnCluster) {
            panel.createActionLabel("Diff", DiffAction.ID)
        }
        panel.createActionLabel("Ignore") {
            editor.hideNotification(PullNotification.KEY_PANEL, project)
        }

        return panel
    }
}