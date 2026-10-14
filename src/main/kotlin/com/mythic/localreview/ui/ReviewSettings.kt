package com.mythic.localreview.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path

@Service(Service.Level.APP)
@State(name = "LocalReviewSettings", storages = [Storage("local-review.xml")])
class ReviewSettings : PersistentStateComponent<ReviewSettings.State> {
    class State {
        /** Folder holding all review folders. Blank = `~/.local-review`. */
        var storeRoot: String = ""
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    var storeRootSetting: String
        get() = state.storeRoot
        set(v) { state.storeRoot = v.trim() }

    fun storeRoot(): Path =
        if (state.storeRoot.isBlank()) Path.of(System.getProperty("user.home"), ".local-review") else Path.of(state.storeRoot)

    companion object {
        fun getInstance(): ReviewSettings = ApplicationManager.getApplication().getService(ReviewSettings::class.java)
    }
}

class ReviewSettingsConfigurable : BoundSearchableConfigurable("Local Review", "com.mythic.localreview.settings") {
    override fun createPanel(): DialogPanel {
        val settings = ReviewSettings.getInstance()
        return panel {
            row("Review store folder:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Review Store Folder"))
                    .columns(40)
                    .bindText(settings::storeRootSetting)
                    .comment("Each review is a git repository of plain JSON files in <folder>/<repo>/<review>. Leave blank for ~/.local-review. Sync it with any file sync tool or a git remote.")
            }
        }
    }
}
