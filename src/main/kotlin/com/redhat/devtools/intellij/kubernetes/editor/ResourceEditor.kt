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
package com.redhat.devtools.intellij.kubernetes.editor

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.redhat.devtools.intellij.kubernetes.editor.notification.DeletedNotification
import com.redhat.devtools.intellij.kubernetes.editor.notification.ErrorNotification
import com.redhat.devtools.intellij.kubernetes.editor.notification.PullNotification
import com.redhat.devtools.intellij.kubernetes.editor.notification.PulledNotification
import com.redhat.devtools.intellij.kubernetes.editor.notification.PushNotification
import com.redhat.devtools.intellij.kubernetes.editor.util.getDocument
import com.redhat.devtools.intellij.kubernetes.model.Clients
import com.redhat.devtools.intellij.kubernetes.model.ClusterResource
import com.redhat.devtools.intellij.kubernetes.model.ModelChangeObservable
import com.redhat.devtools.intellij.kubernetes.model.Notification
import com.redhat.devtools.intellij.kubernetes.model.ResourceException
import com.redhat.devtools.intellij.kubernetes.model.util.isKubernetesResource
import com.redhat.devtools.intellij.kubernetes.model.util.isSameResource
import com.redhat.devtools.intellij.kubernetes.model.util.trimWithEllipsis
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.Serialization
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An adapter for [FileEditor] instances that allows to push or load the editor content to/from a remote cluster.
 */
open class ResourceEditor constructor(
    var localCopy: HasMetadata?,
    val editor: FileEditor,
    private val project: Project,
    private val clients: Clients<out KubernetesClient>,
    // for mocking purposes
    private val createResource: (editor: FileEditor, clients: Clients<out KubernetesClient>) -> HasMetadata? =
        EditorResourceFactory::create,
    // for mocking purposes
    private val createClusterResource: (resource: HasMetadata, clients: Clients<out KubernetesClient>) -> ClusterResource =
        { resource, clients -> ClusterResource(resource, clients) },
    // for mocking purposes
    private val createResourceFileForVirtual: (file: VirtualFile?) -> ResourceFile? =
        ResourceFile.Factory::create,
    private val isTemporary: (file: VirtualFile?) -> Boolean? =
        ResourceFile.Factory::isTemporary,
    // for mocking purposes
    private val pushNotification: PushNotification = PushNotification(editor, project),
    // for mocking purposes
    private val pullNotification: PullNotification = PullNotification(editor, project),
    // for mocking purposes
    private val pulledNotification: PulledNotification = PulledNotification(editor, project),
    // for mocking purposes
    private val deletedNotification: DeletedNotification = DeletedNotification(editor, project),
    // for mocking purposes
    private val errorNotification: ErrorNotification = ErrorNotification(editor, project),
    // for mocking purposes
    private val documentProvider: (FileEditor) -> Document? = ::getDocument,
    // for mocking purposes
    private val psiDocumentManagerProvider: (Project) -> PsiDocumentManager = { PsiDocumentManager.getInstance(project) },
    // for mocking purposes
    private val ideNotification: Notification = Notification(),
    private val documentReplaced: AtomicBoolean = AtomicBoolean(false)
) {

    companion object {
        const val ID_TOOLBAR = "Kubernetes.Editor.Toolbar"

        @JvmStatic
        val factory = ResourceEditorFactory()
    }

    open var editorResource: HasMetadata? = localCopy
        get() {
            resourceChangeMutex.withLock {
                return field
            }
        }
        set(resource) {
            resourceChangeMutex.withLock {
                field = resource
            }
        }
    /** mutex to exclude concurrent execution of push & watch notification **/
    private val resourceChangeMutex = ReentrantLock()
    private var oldClusterResource: ClusterResource? = null
    private var _clusterResource: ClusterResource? = null
    private val clusterResource: ClusterResource?
        get() {
            return resourceChangeMutex.withLock {
                if (_clusterResource == null
                    // create new cluster resource if editor has different resource (name, kind, etc. changed)
                    || false == _clusterResource?.isSameResource(editorResource)
                ) {
                    oldClusterResource = _clusterResource
                    oldClusterResource?.close()
                    _clusterResource = createClusterResource(editorResource, clients)
                }
                _clusterResource
            }
        }

    /**
     * Updates this editor notifications and title. Does nothing if is called right after [replaceDocument].
     *
     * @see [replaceDocument]
     */
    fun update() {
        try {
            if (documentReplaced.compareAndSet(true, false)) {
                /**
                 * update triggered by [replaceDocument]
                 **/
                return
            }
            val resource = createResource.invoke(editor, clients) ?: return
            this.editorResource = resource
            update(resource, clusterResource)
        } catch (e: ResourceException) {
            showErrorNotification(e)
        }
    }

    private fun update(resource: HasMetadata?, clusterResource: ClusterResource?) {
        if (resource == null
            || clusterResource == null) {
            return
        }
        showNotifications(resource, clusterResource)
    }

    private fun showNotifications(
        resource: HasMetadata,
        clusterResource: ClusterResource
    ) {
        when {
            clusterResource.isDeleted()
                    && !clusterResource.isModified(resource) -> {
                hideNotifications()
                deletedNotification.show(resource)
            }
            clusterResource.isOutdated(resource) -> {
                hideNotifications()
                showPulledOrPullNotification(resource)
            }
            clusterResource.canPush(resource) -> {
                hideNotifications()
                pushNotification.show()
            }
            else -> hideNotifications()
        }
    }

    private fun hideNotifications() {
        errorNotification.hide()
        pullNotification.hide()
        deletedNotification.hide()
        pushNotification.hide()
        pulledNotification.hide()
    }

    private fun showPulledOrPullNotification(resource: HasMetadata) {
        val resourceOnCluster = clusterResource?.get(false)
        if (resourceOnCluster != null) {
            if (!hasLocalChanges(resource)) {
                replaceDocument(resourceOnCluster)
                resourceChangeMutex.withLock {
                    this.editorResource = resource
                    this.localCopy = resource
                }
            } else {
                pullNotification.show(resourceOnCluster)
            }
        }
    }

    /**
     * Returns `true` if the given resource is dirty aka has modifications that were not pushed.
     *
     * @param resource to be checked for modification
     * @return true if the resource is dirty
     */
    private fun hasLocalChanges(resource: HasMetadata): Boolean {
        return resourceChangeMutex.withLock {
            resource != this.localCopy
        }
    }

    private fun showErrorNotification(e: Throwable) {
        hideNotifications()
        errorNotification.show(e.message ?: "", e.cause?.message)
    }

    /**
     * Pulls the resource from the cluster and replaces the content of this editor.
     * Does nothing if it doesn't exist.
     */
    fun pull() {
        hideNotifications()
        val pulledResource = resourceChangeMutex.withLock {
            val pulled = clusterResource?.get() ?: return
            /**
             * set editor resource now,
             * watch change modification notification can get in before document was replaced
             */
            this.editorResource = pulled
            this.localCopy = pulled
            pulled
        }
        replaceDocument(pulledResource)
    }

    private fun replaceDocument(resource: HasMetadata?) {
        if (resource == null) {
            return
        }
        val document = documentProvider.invoke(editor) ?: return
        val jsonYaml = Serialization.asYaml(resource).trim()
        if (document.text.trim() != jsonYaml) {
            executeWriteAction {
                document.replaceString(0, document.textLength - 1, jsonYaml)
                documentReplaced.set(true)
                val psiDocumentManager = psiDocumentManagerProvider.invoke(project)
                psiDocumentManager.commitDocument(document)
                pulledNotification.show(resource)
            }
        }
    }

    /**
     * Returns `true` if the resource in this editor exists on the cluster. Returns `false` otherwise.
     * @return true if the resource in this editor exists on the cluster
     */
    fun existsOnCluster(): Boolean {
        return clusterResource?.exists() ?: false
    }

    fun isOutdated(): Boolean {
        val resource = createResource.invoke(editor, clients) ?: return false
        this.editorResource = resource
        return clusterResource?.isOutdated(resource) ?: false
    }

    /**
     * Pushes the editor content to the cluster.
     */
    fun push() {
        try {
            val resource = createResource.invoke(editor, clients) ?: return
            this.editorResource = resource
            push(resource, clusterResource)
        } catch (e: Exception) {
            showErrorNotification(e)
        }
    }

    private fun push(resource: HasMetadata?, clusterResource: ClusterResource?) {
        try {
            if (resource == null
                || clusterResource == null
            ) {
                return
            }
            hideNotifications()
            val updatedResource = resourceChangeMutex.withLock {
                val updated = clusterResource.push(resource)
                /**
                 * set editor resource now using lock,
                 * resource watch change modification notification can get in before document was replaced
                 */
                this.editorResource = updated
                this.localCopy = updated
                updated
            }
            replaceDocument(updatedResource)
        } catch (e: ResourceException) {
            logger<ResourceEditor>().warn(e)
            ideNotification.error(
                "Could not save ${
                    if (resource != null) {
                        "${resource.kind} ${resource.metadata.name}"
                    } else {
                        ""
                    }
                } to cluster",
                trimWithEllipsis(e.message, 300) ?: ""
            )
        }
    }

    fun startWatch(): ResourceEditor {
        clusterResource?.watch()
        return this
    }

    fun stopWatch() {
        clusterResource?.stopWatch()
    }

    private fun createClusterResource(resource: HasMetadata?, clients: Clients<out KubernetesClient>): ClusterResource? {
        if (resource == null) {
            return null
        }
        val clusterResource = createClusterResource.invoke(resource, clients)
        clusterResource.addListener(onResourceChanged())
        clusterResource.watch()
        return clusterResource
    }

    private fun onResourceChanged(): ModelChangeObservable.IResourceChangeListener {
        return object : ModelChangeObservable.IResourceChangeListener {
            override fun added(added: Any) {
                // ignore
            }

            override fun removed(removed: Any) {
                showNotifications()
            }

            override fun modified(modified: Any) {
                showNotifications()
            }

            private fun showNotifications() {
                val pair = resourceChangeMutex.withLock {
                    val sameResource = editorResource?.isSameResource(localCopy) ?: false
                    Pair(sameResource, editorResource)
                }
                if (clusterResource != null
                    && pair.first
                    && pair.second != null
                ) {
                    showNotifications(pair.second!!, clusterResource!!)
                }
            }
        }
    }

    /**
     * Closes this instance. closes the resource watch and deletes the temporary file if one was created.
     */
    fun close() {
        clusterResource?.close()
        createResourceFileForVirtual(editor.file)?.deleteTemporary()
    }

    /**
     * Enables editing of non project files for the file in this editor. This prevents the IDE from presenting the
     * "edit non-project" files dialog.
     */
    fun enableNonProjectFileEditing() {
        if (editor.file == null
            || !hasKubernetesResource(editor)) {
                return
        }
        createResourceFileForVirtual(editor.file)?.enableNonProjectFileEditing()
    }

    protected open fun hasKubernetesResource(editor: FileEditor): Boolean {
        val document = documentProvider.invoke(editor) ?: return false
        return isKubernetesResource(document.text)
    }

    fun getTitle(): String? {
        val file = editor.file ?: return null
        return if (true == isTemporary.invoke(file)
            && hasKubernetesResource(editor)) {
            val resource = editorResource ?: return null
            getTitleFor(resource)
        } else {
            getTitleFor(file)
        }
    }

    private fun getTitleFor(file: VirtualFile): String {
        return file.name
    }

    private fun getTitleFor(resource: HasMetadata): String {
        return when (resource) {
            is Namespace,
            is io.fabric8.openshift.api.model.Project -> resource.metadata.name
            else -> {
                if (resource.metadata.namespace != null) {
                    "${resource.metadata.name}@${resource.metadata.namespace}"
                } else {
                    resource.metadata.name
                }
            }
        }
    }

    protected open fun executeWriteAction(runnable: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, runnable)
    }

    fun createToolbar() {
        var editorToolbar: ActionToolbar? = editor.getUserData(ResourceEditorFactory.KEY_TOOLBAR)
        if (editorToolbar == null) {
            editorToolbar = EditorToolbarFactory.create(ID_TOOLBAR, editor, project)
            editor.putUserData(ResourceEditorFactory.KEY_TOOLBAR, editorToolbar)
        }
    }
}

