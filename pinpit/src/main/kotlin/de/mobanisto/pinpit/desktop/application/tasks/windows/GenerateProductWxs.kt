/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.windows

import de.mobanisto.pinpit.desktop.application.tasks.windows.GenerateFilesWxs.FileEntry
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class GenerateProductWxs(
    private val output: Path,
    private val upgradeCode: String,
    private val vendor: String,
    private val name: String,
    private val version: String,
    private val aumid: String?,
    private val description: String,
    private val mainExecutable: FileEntry,
    private val bitmapBanner: Path?,
    private val bitmapDialog: Path?,
    private val icon: Path,
    private val shortcut: Boolean,
    private val menuFolder: String?,
    private val perUserInstall: Boolean,
) {

    fun execute() {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = documentBuilder.newDocument()

        createDocument(doc)

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val source = DOMSource(doc)
        val os = Files.newOutputStream(output)
        os.use {
            val result = StreamResult(it)
            transformer.transform(source, result)
        }
    }

    private fun createDocument(doc: Document) {
        val iconId = "app_icon"

        val aumid = this.aumid ?: "$vendor.$name"
        val productId = UUID.nameUUIDFromBytes("$vendor/$name/$version".toByteArray(Charsets.UTF_8))
        val wix = doc.createElement("Wix").apply {
            setAttribute("xmlns", "http://schemas.microsoft.com/wix/2006/wi")
            doc.appendChild(this)
        }
        val product = wix.createChild("Product") {
            setAttribute("Id", productId.toString())
            setAttribute("Name", name)
            setAttribute("Version", version)
            setAttribute("Manufacturer", vendor)
            setAttribute("Language", "1033")
            setAttribute("UpgradeCode", upgradeCode)
        }
        product.createChild("Package") {
            setAttribute("Description", description)
            setAttribute("Manufacturer", vendor)
            setAttribute("InstallerVersion", "200")
            setAttribute("Compressed", "yes")
            setAttribute("InstallScope", if (perUserInstall) "perUser" else "perMachine")
            // setAttribute("Platform", "x64") // Use of this switch is discouraged in favor of the -arch switch
        }
        product.createChild("MajorUpgrade") {
            setAttribute(
                "DowngradeErrorMessage",
                "A later version of $name is already installed. Setup will now exit."
            )
        }
        if (bitmapBanner != null) {
            product.createChild("WixVariable", "WixUIBannerBmp") {
                setAttribute("Value", bitmapBanner.toString())
            }
        }
        if (bitmapDialog != null) {
            product.createChild("WixVariable", "WixUIDialogBmp") {
                setAttribute("Value", bitmapDialog.toString())
            }
        }
        product.createChild("MediaTemplate") {
            setAttribute("EmbedCab", "yes")
        }
        product.createChild("Directory", "TARGETDIR") {
            setAttribute("Name", "SourceDir")
        }
        if (shortcut) {
            shortcut(product, aumid, iconId)
        }
        product.createChild("Feature", "MainFeature") {
            createChild("ComponentGroupRef", "Files")
            if (shortcut) {
                createChild("ComponentRef", "ApplicationShortcut")
            }
        }
        product.createChild("Property", "WIXUI_INSTALLDIR") {
            setAttribute("Value", "INSTALLDIR")
        }
        product.createChild("UIRef", "InstallUI")

        product.createChild("Icon", iconId) {
            setAttribute("SourceFile", icon.toString())
        }
        product.createChild("Property", "ARPPRODUCTICON") {
            setAttribute("Value", iconId)
        }
    }

    private fun shortcut(product: Element, aumid: String, iconId: String) {
        val withMenuFolder = menuFolder != null

        product.createChild("DirectoryRef", "TARGETDIR") {
            createChild("Directory", "ProgramMenuFolder") {
                if (withMenuFolder) {
                    createChild("Directory", "ApplicationProgramsFolder") {
                        setAttribute("Name", menuFolder)
                    }
                }
            }
        }
        if (withMenuFolder) {
            shortcut(product, "ApplicationProgramsFolder", true, aumid, iconId)
        } else {
            shortcut(product, "ProgramMenuFolder", false, aumid, iconId)
        }
    }

    private fun shortcut(product: Element, folderId: String, withMenuFolder: Boolean, aumid: String, iconId: String) {
        product.createChild("DirectoryRef", folderId) {
            createChild("Component", "ApplicationShortcut") {
                val uuid = UUID.randomUUID()
                setAttribute("Guid", "{$uuid}")
                createChild("Shortcut", "ApplicationStartMenuShortcut") {
                    setAttribute("Name", name)
                    setAttribute("Description", description)
                    setAttribute("Target", "[#${mainExecutable.fileId}]")
                    setAttribute("WorkingDirectory", "APPLICATIONROOTDIRECTORY")
                    setAttribute("Icon", iconId)
                    createChild("ShortcutProperty") {
                        setAttribute("Key", "System.AppUserModel.ID")
                        setAttribute("Value", aumid)
                    }
                }
                if (withMenuFolder) {
                    createChild("RemoveFolder", "CleanupShortcut") {
                        setAttribute("Directory", "ApplicationProgramsFolder")
                        setAttribute("On", "uninstall")
                    }
                }
                createChild("RegistryValue") {
                    setAttribute("Root", "HKCU")
                    setAttribute("Key", "Software\\$vendor\\$name")
                    setAttribute("Name", "installed")
                    setAttribute("Type", "integer")
                    setAttribute("Value", "1")
                    setAttribute("KeyPath", "yes")
                }
            }
        }
    }
}
