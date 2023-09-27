// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.ui.connection

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AppIcon
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import icons.AwsIcons
import software.aws.toolkits.core.ClientConnectionSettings
import software.aws.toolkits.jetbrains.core.credentials.sono.SonoCredentialManager
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenProviderListener
import software.aws.toolkits.jetbrains.services.caws.CawsEndpoints
import software.aws.toolkits.resources.message
import javax.swing.JComponent

open class SonoLoginOverlay(
    private val project: Project?,
    private val disposable: Disposable,
    private val drawPostLoginContent: (SonoLoginOverlay.(ClientConnectionSettings<*>) -> JComponent)
) :
    NonOpaquePanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, true)) {

    private val contentWrapper = Wrapper()
    private val loginSubpanel by lazy {
        // TODO: pending final UX
        panel {
            row {
                label(message("code.aws")).applyToComponent {
                    icon = AwsIcons.Logos.AWS_SMILE_LARGE
                    font = JBFont.h2()
                }
            }

            row {
                label(message("code.aws.value_prop_text"))
            }

            row {
                browserLink(message("aws.settings.learn_more"), CawsEndpoints.ConsoleFactory.marketing())
            }

            row {
                button(message("caws.login")) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        SonoCredentialManager.loginSono(project)
                    }
                }.apply {
                    applyToComponent {
                        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                    }
                }
            }
        }.andTransparent()
    }

    init {
        border = null
        add(contentWrapper)

        redraw()

        ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(
            BearerTokenProviderListener.TOPIC,
            object : BearerTokenProviderListener {
                override fun onChange(providerId: String) {
                    redraw()
                }
            }
        )
    }

    open fun initBorders() {}

    fun redraw() {
        with(contentWrapper.targetComponent) {
            if (this is Disposable) {
                Disposer.dispose(this)
            }
        }

        val connectionSettings = SonoCredentialManager.getInstance().getConnectionSettings()

        // specify 'any' because if we're currently in a modal dialog, we noop until the dialog is closed
        runInEdt(ModalityState.any()) {
            initBorders()
            if (connectionSettings != null) {
                AppIcon.getInstance().requestAttention(null, false)
                contentWrapper.setContent(drawPostLoginContent(connectionSettings))
            } else {
                contentWrapper.setContent(loginSubpanel)
            }
        }
    }
}
